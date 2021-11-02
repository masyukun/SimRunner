package org.schambon.loadsimrunner;

import java.util.List;

import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;

import org.bson.Document;
import org.schambon.loadsimrunner.report.Reporter;

public class UpdateManyRunner extends AbstractUpdateRunner {

    public UpdateManyRunner(WorkloadManager workloadConfiguration, Reporter reporter) {
        super(workloadConfiguration, reporter);
    }

    @Override
    protected UpdateResult doUpdate(Document filter, Document update, UpdateOptions options) {
        return mongoColl.updateMany(filter, update, options);
    }

    @Override
    protected UpdateResult doUpdate(Document filter, List<Document> update, UpdateOptions options) {
        return mongoColl.updateMany(filter, update, options);
    }

}
