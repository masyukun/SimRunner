package org.schambon.loadsimrunner.runner;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.commons.lang3.NotImplementedException;
import org.bson.Document;
import org.schambon.loadsimrunner.WorkloadManager;
import org.schambon.loadsimrunner.errors.InvalidConfigException;
import org.schambon.loadsimrunner.report.Reporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TimeSeriesRunner extends AbstractRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeSeriesRunner.class);

    Instant currentTime = null;
    ExecutorService exec = null;

    Document timeConfig;
    Document metaConfig;

    public TimeSeriesRunner(WorkloadManager workloadConfiguration, Reporter reporter) {
        super(workloadConfiguration, reporter);

        this.timeConfig = (Document) params.get("time");
        if (timeConfig.containsKey("start")) {
            currentTime =  ((Date) timeConfig.get("start")).toInstant();
        }

        this.metaConfig = (Document) params.get("meta");

        var workers = params.getInteger("workers", 1);
        exec = Executors.newFixedThreadPool(workers);
    }

    @Override
    protected long doRun() {

        Instant base = null;
        if (timeConfig.containsKey("value")) {
            var value = template.generateExpression(timeConfig.get("value"));
            if (value instanceof Date) {
                base = ((Date)value).toInstant();
            } else if (value instanceof Long) {
                base = Instant.ofEpochMilli((Long)value);
            } else {
                LOGGER.info("Time value resolved to {}, ignoring", value.getClass().getName());
            }
        }
        if (base == null) {
            if (currentTime != null) {
                long step;
                var stepC = timeConfig.get("step");
                if (stepC instanceof Number) {
                    step = ((Number)stepC).longValue();
                } else {
                    LOGGER.info("Step resolved to {}, defaulting to 1000ms", stepC);
                    step = 1000l;
                }

                currentTime = currentTime.plus(step, ChronoUnit.MILLIS);
                base = currentTime;
            } else {
                LOGGER.error("Invalid timeseries configuration, no start/step and no value. Aborting");
                throw new InvalidConfigException("Invalid timeseries configuration, no start/step and no value");
            }
        } 

        if (timeConfig.containsKey("stop")) {
            var stop = ((Date)timeConfig.get("stop")).toInstant();

            if (base.isAfter(stop)) {
                LOGGER.info("TimeSeriesRunner {} has run beyond stop date", name);
                return 0;
            }
        }

        // now we have a base
        String generateOption = metaConfig.getString("generate");
        if (generateOption == null || "all".equals(generateOption)) {
            var series = template.dictionary(metaConfig.getString("dictionary"));
            if (series == null) {
                LOGGER.error("Series dictionary {} not found", metaConfig.getString("dictionary"));
                throw new InvalidConfigException("Series dictionary");
            }

            // only support insertType: "single" now
            // TODO insertType: "batch"

            List<Callable<Void>> tasks = new ArrayList<>();
            for (var metaVal: series) {
                Instant ts;
                if (timeConfig.containsKey("jitter") && timeConfig.get("jitter") instanceof Number) {
                    long maxJitter = ((Number) timeConfig.get("jitter")).longValue();
                    long jitter = ThreadLocalRandom.current().nextLong(maxJitter);
                    long flip = ThreadLocalRandom.current().nextInt(2);
                    if (flip == 0) {
                        ts = base.plus(jitter, ChronoUnit.MILLIS);
                    } else {
                        ts = base.minus(jitter, ChronoUnit.MILLIS);
                    }
                } else {
                    ts = base;
                }

                var doc = template.generate();
                doc.append(metaConfig.getString("metaField"), metaVal);
                doc.append(timeConfig.getString("timeField"), ts);

                tasks.add(() -> {
                    var _s = System.currentTimeMillis();
                    mongoColl.insertOne(doc);
                    reporter.reportOp(name, 1, System.currentTimeMillis() - _s);
                    return null;
                });
            }
            try {
                var _s = System.currentTimeMillis();
                List<Future<Void>> futures = exec.invokeAll(tasks);
                for (var f : futures) {
                    f.get();
                }
                return System.currentTimeMillis() - _s;
            } catch (InterruptedException|ExecutionException e) {
                LOGGER.error("Interrupted", e);
                throw new RuntimeException(e);
            }

        } else {
            throw new NotImplementedException("Timeseries generation option {} not supported", generateOption);
        }
    }
    
}
