package com.conveyal.datatools.manager.gtfsplus;

import com.conveyal.datatools.common.utils.Consts;
import com.conveyal.datatools.manager.DataManager;
import com.conveyal.datatools.manager.models.FeedVersion;
import com.conveyal.datatools.manager.persistence.FeedStore;
import com.conveyal.datatools.manager.persistence.Persistence;
import com.conveyal.gtfs.GTFSFeed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.io.input.BOMInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Generates a GTFS+ validation report for a file. */
public class GtfsPlusValidation implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(GtfsPlusValidation.class);
    private static final FeedStore gtfsPlusStore = new FeedStore(DataManager.GTFS_PLUS_SUBDIR);
    private static final String NOT_FOUND = "not found in GTFS";

    // Public fields to appear in validation JSON.
    public final String feedVersionId;
    /** Indicates whether GTFS+ validation applies to user-edited feed or original published GTFS feed */
    public boolean published;
    public long lastModified;
    /** Issues found for this GTFS+ feed */
    public List<ValidationIssue> issues = new LinkedList<>();

    private GtfsPlusValidation (String feedVersionId) {
        this.feedVersionId = feedVersionId;
    }

    /**
     * Validate a GTFS+ feed and return a list of issues encountered.
     * FIXME: For now this uses the MapDB-backed GTFSFeed class. Which actually suggests that this might
     *   should be contained within a MonitorableJob.
     */
    public static GtfsPlusValidation validate(String feedVersionId) throws Exception {
        GtfsPlusValidation validation = new GtfsPlusValidation(feedVersionId);
        if (!DataManager.isModuleEnabled("gtfsplus")) {
            throw new IllegalStateException("GTFS+ module must be enabled in server.yml to run GTFS+ validation.");
        }
        LOG.info("Validating GTFS+ for " + feedVersionId);

        FeedVersion feedVersion = Persistence.feedVersions.getById(feedVersionId);
        // Load the main GTFS file.
        // FIXME: Swap MapDB-backed GTFSFeed for use of SQL data?
        String gtfsFeedDbFilePath = gtfsPlusStore.getPathToFeed(feedVersionId + ".db");
        GTFSFeed gtfsFeed;
        try {
            // This check for existence must occur before GTFSFeed is instantiated (and the file must be discarded
            // immediately).
            boolean dbExists = new File(gtfsFeedDbFilePath).isFile();
            gtfsFeed = new GTFSFeed(gtfsFeedDbFilePath);
            if (!dbExists) {
                LOG.info("Loading GTFS file into new MapDB file (.db).");
                gtfsFeed.loadFromFile(new ZipFile(feedVersion.retrieveGtfsFile().getAbsolutePath()));
            }
        } catch (Exception e) {
            LOG.error("MapDB file for GTFSFeed appears to be corrupted. Deleting and trying to load from zip file.", e);
            // Error loading MapDB file. Delete and try to reload.
            new File(gtfsFeedDbFilePath).delete();
            new File(gtfsFeedDbFilePath + ".p").delete();
            LOG.info("Attempt #2 to load GTFS file into new MapDB file (.db).");
            gtfsFeed = new GTFSFeed(gtfsFeedDbFilePath);
            gtfsFeed.loadFromFile(new ZipFile(feedVersion.retrieveGtfsFile().getAbsolutePath()));
        }

        // check for saved GTFS+ data
        File file = gtfsPlusStore.getFeed(feedVersionId);
        if (file == null) {
            validation.published = true;
            LOG.warn("GTFS+ Validation -- Modified GTFS+ file not found, loading from main version GTFS.");
            file = feedVersion.retrieveGtfsFile();
        } else {
            validation.published = false;
            LOG.info("GTFS+ Validation -- Validating user-saved GTFS+ data (unpublished)");
        }
        int gtfsPlusTableCount = 0;
        ZipFile zipFile = new ZipFile(file);
        final Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            final ZipEntry entry = entries.nextElement();
            for (int i = 0; i < DataManager.gtfsPlusConfig.size(); i++) {
                JsonNode tableNode = DataManager.gtfsPlusConfig.get(i);
                if (tableNode.get("name").asText().equals(entry.getName())) {
                    LOG.info("Validating GTFS+ table: " + entry.getName());
                    gtfsPlusTableCount++;
                    // Skip any byte order mark that may be present. Files must be UTF-8,
                    // but the GTFS spec says that "files that include the UTF byte order mark are acceptable".
                    InputStream bis = new BOMInputStream(zipFile.getInputStream(entry));
                    validateTable(validation.issues, tableNode, bis, gtfsFeed);
                }
            }
        }
        gtfsFeed.close();
        LOG.info("GTFS+ tables found: {}/{}", gtfsPlusTableCount, DataManager.gtfsPlusConfig.size());
        return validation;
    }

    /**
     * Validate a single GTFS+ table using the table specification found in gtfsplus.yml.
     */
    private static void validateTable(
        Collection<ValidationIssue> issues,
        JsonNode specTable,
        InputStream inputStreamToValidate,
        GTFSFeed gtfsFeed
    ) throws IOException {
        String tableId = specTable.get("id").asText();
        // Read in table data from input stream.
        BufferedReader in = new BufferedReader(new InputStreamReader(inputStreamToValidate));
        String line = in.readLine();
        String[] inputHeaders = line.split(",");
        List<String> fieldList = Arrays.asList(inputHeaders);
        JsonNode[] fieldsFound = new JsonNode[inputHeaders.length];
        JsonNode specFields = specTable.get("fields");
        // Iterate over spec fields and check that there are no missing required fields.
        for (int i = 0; i < specFields.size(); i++) {
            JsonNode specField = specFields.get(i);
            String fieldName = specField.get("name").asText();
            int index = fieldList.indexOf(fieldName);
            if (index != -1) {
                // Add spec field for each field found.
                fieldsFound[index] = specField;
            } else if (isRequired(specField)) {
                // If spec field not found, check that missing field was not required.
                issues.add(new ValidationIssue(tableId, fieldName, -1, "Required column missing."));
            }
        }
        // Iterate over each row and validate each field value.
        int rowIndex = 0;
        int rowsWithWrongNumberOfColumns = 0;
        while ((line = in.readLine()) != null) {
            String[] values = line.split(Consts.COLUMN_SPLIT, -1);
            // First, check that row has the correct number of fields.
            if (values.length != fieldsFound.length) {
                rowsWithWrongNumberOfColumns++;
            }
            // Validate each value in row. Note: we iterate over the fields and not values because a row may be missing
            // columns, but we still want to validate that missing value (e.g., if it is missing a required field).
            for (int f = 0; f < fieldsFound.length; f++) {
                // If value exists for index, use that. Otherwise, default to null to avoid out of bounds exception.
                String val = f < values.length ? values[f] : null;
                validateTableValue(issues, tableId, rowIndex, val, fieldsFound[f], gtfsFeed);
            }
            rowIndex++;
        }
        // Add issue for wrong number of columns after processing all rows.
        // Note: We considered adding an issue for each row, but opted for the single error approach because there's no
        // concept of a row-level issue in the UI right now. So we would potentially need to add that to the UI
        // somewhere. Also, there's the trouble of reporting the issue at the row level, but not really giving the user
        // a great way to resolve the issue in the GTFS+ editor. Essentially, all of the rows with the wrong number of
        // columns can be resolved simply by clicking the "Save and Revalidate" button -- so the resolution is more at
        // the table level than the row level (like, for example, a bad value for a field would be).
        if (rowsWithWrongNumberOfColumns > 0) {
            issues.add(new ValidationIssue(tableId, null, -1, rowsWithWrongNumberOfColumns + " row(s) do not contain the same number of fields as there are headers. (File may need to be edited manually.)"));
        }
    }

    /** Determine if a GTFS+ spec field is required. */
    private static boolean isRequired(JsonNode specField) {
        return specField.get("required") != null && specField.get("required").asBoolean();
    }

    /** Validate a single value for a GTFS+ table. */
    private static void validateTableValue(
        Collection<ValidationIssue> issues,
        String tableId,
        int rowIndex,
        String value,
        JsonNode specField,
        GTFSFeed gtfsFeed
    ) {
        if (specField == null) return;
        String fieldName = specField.get("name").asText();

        if (isRequired(specField)) {
            if (value == null || value.length() == 0) {
                issues.add(new ValidationIssue(tableId, fieldName, rowIndex, "Required field missing value"));
            }
        }

        switch(specField.get("inputType").asText()) {
            case "DROPDOWN":
                boolean invalid = true;
                ArrayNode options = (ArrayNode) specField.get("options");
                for (JsonNode option : options) {
                    String optionValue = option.get("value").asText();

                    // NOTE: per client's request, this check has been made case insensitive
                    boolean valuesAreEqual = optionValue.equalsIgnoreCase(value);

                    // if value is found in list of options, break out of loop
                    if (valuesAreEqual || (!isRequired(specField) && "".equals(value))) {
                        invalid = false;
                        break;
                    }
                }
                if (invalid) {
                    issues.add(new ValidationIssue(tableId, fieldName, rowIndex, "Value: " + value + " is not a valid option."));
                }
                break;
            case "TEXT":
                // check if value exceeds max length requirement
                if (specField.get("maxLength") != null) {
                    int maxLength = specField.get("maxLength").asInt();
                    if (value != null && value.length() > maxLength) {
                        issues.add(new ValidationIssue(tableId, fieldName, rowIndex, "Text value exceeds the max. length of " + maxLength));
                    }
                }
                break;
            case "GTFS_ROUTE":
                if (!gtfsFeed.routes.containsKey(value)) {
                    issues.add(new ValidationIssue(tableId, fieldName, rowIndex, missingIdText(value, "Route")));
                }
                break;
            case "GTFS_STOP":
                if (!gtfsFeed.stops.containsKey(value)) {
                    issues.add(new ValidationIssue(tableId, fieldName, rowIndex, missingIdText(value, "Stop")));
                }
                break;
            case "GTFS_TRIP":
                if (!gtfsFeed.trips.containsKey(value)) {
                    issues.add(new ValidationIssue(tableId, fieldName, rowIndex, missingIdText(value, "Trip")));
                }
                break;
            case "GTFS_FARE":
                if (!gtfsFeed.fares.containsKey(value)) {
                    issues.add(new ValidationIssue(tableId, fieldName, rowIndex, missingIdText(value, "Fare")));
                }
                break;
            case "GTFS_SERVICE":
                if (!gtfsFeed.services.containsKey(value)) {
                    issues.add(new ValidationIssue(tableId, fieldName, rowIndex, missingIdText(value, "Service")));
                }
                break;
        }

    }

    /** Construct missing ID text for validation issue description. */
    private static String missingIdText(String value, String entity) {
        return String.join(" ", entity, "ID", value, NOT_FOUND);
    }
}
