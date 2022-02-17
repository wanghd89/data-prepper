/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.dataprepper.plugins.processor.date;

import com.amazon.dataprepper.metrics.PluginMetrics;
import com.amazon.dataprepper.model.annotations.DataPrepperPlugin;
import com.amazon.dataprepper.model.annotations.DataPrepperPluginConstructor;
import com.amazon.dataprepper.model.event.Event;
import com.amazon.dataprepper.model.processor.AbstractProcessor;
import com.amazon.dataprepper.model.processor.Processor;
import com.amazon.dataprepper.model.record.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@DataPrepperPlugin(name = "date", pluginType = Processor.class, pluginConfigurationType = DateProcessorConfig.class)
public class DateProcessor extends AbstractProcessor<Record<Event>, Record<Event>> {
    private static final Logger LOG = LoggerFactory.getLogger(DateProcessor.class);
    private static final ZoneId OUTPUT_TIMEZONE = ZoneId.systemDefault();
    private static final String OUTPUT_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

    private String keyToParse;
    private List<DateTimeFormatter> dateTimeFormatters;
    private final DateProcessorConfig dateProcessorConfig;

    @DataPrepperPluginConstructor
    protected DateProcessor(PluginMetrics pluginMetrics, final DateProcessorConfig dateProcessorConfig) {
        super(pluginMetrics);
        this.dateProcessorConfig = dateProcessorConfig;

        if (dateProcessorConfig.getMatch() != null)
            extractKeyAndFormatters();
    }

    @Override
    public Collection<Record<Event>> doExecute(Collection<Record<Event>> records) {
        for(final Record<Event> record : records) {
            String zonedDateTime = null;

            if (Boolean.TRUE.equals(dateProcessorConfig.getFromTimeReceived()))
                zonedDateTime =  getDateTimeFromTimeReceived(record);

            else if (keyToParse != null && !keyToParse.isEmpty())
                zonedDateTime = getDateTimeFromMatch(record);

            if (zonedDateTime != null)
                record.getData().put(dateProcessorConfig.getDestination(), zonedDateTime);
        }
        return records;
    }

    private void extractKeyAndFormatters() {
        for (DateProcessorConfig.DateMatch entry: dateProcessorConfig.getMatch()) {
            keyToParse = entry.getKey();
            dateTimeFormatters = entry.getPatterns().stream().map(this::getSourceFormatter).collect(Collectors.toList());
        }
    }

    private DateTimeFormatter getSourceFormatter(final String pattern) {
        final LocalDate localDateForDefaultValues = LocalDate.now(dateProcessorConfig.getZonedId());

        return new DateTimeFormatterBuilder()
                .appendPattern(pattern)
                .parseDefaulting(ChronoField.YEAR, localDateForDefaultValues.getYear())
                .parseDefaulting(ChronoField.MONTH_OF_YEAR, localDateForDefaultValues.getMonthValue())
                .parseDefaulting(ChronoField.DAY_OF_MONTH, localDateForDefaultValues.getDayOfMonth())
                .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
                .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
                .toFormatter(dateProcessorConfig.getSourceLocale())
                .withZone(dateProcessorConfig.getZonedId());
    }

    private String getDateTimeFromTimeReceived(final Record<Event> record) {
        final Instant timeReceived = record.getData().getMetadata().getTimeReceived();
        return timeReceived.atZone(OUTPUT_TIMEZONE).format(getOutputFormatter());
    }

    private String getDateTimeFromMatch(final Record<Event> record) {
        String sourceTimestamp = getSourceTimestamp(record);
        if (sourceTimestamp == null)
            return null;

        return getFormattedDateTimeString(sourceTimestamp);
    }

    private String getSourceTimestamp(final Record<Event> record) {
        try {
            return record.getData().get(keyToParse, String.class);
        } catch (Exception e) {
            LOG.debug("Unable to find {} in event data.", keyToParse);
            return null;
        }
    }

    private String getFormattedDateTimeString(final String sourceTimestamp) {
        for (DateTimeFormatter formatter : dateTimeFormatters) {
            try {
                return ZonedDateTime.parse(sourceTimestamp, formatter).format(getOutputFormatter().withZone(OUTPUT_TIMEZONE));
            } catch (Exception ignored) {

            }
        }

        LOG.debug("Unable to parse {} with any of the provided patterns", sourceTimestamp);
        return null;
    }

    private DateTimeFormatter getOutputFormatter() {
        return DateTimeFormatter.ofPattern(OUTPUT_FORMAT);
    }

    @Override
    public void prepareForShutdown() {

    }

    @Override
    public boolean isReadyForShutdown() {
        return true;
    }

    @Override
    public void shutdown() {

    }
}