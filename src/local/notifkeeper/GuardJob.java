package local.notifkeeper;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Every 6 hours: checks that Gmail is still the full app and that Notification Keeper still has
 * notification access (the listener itself can't notice losing access - it just stops running).
 */
public class GuardJob extends JobService {
    private static final int JOB_ID = 1;
    private static final long PERIOD_MS = 6L * 60 * 60 * 1000;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_ID) != null) return; // never re-schedule a running/pending job
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, GuardJob.class))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Keeper.runGuard(this);
        return false; // done synchronously (only quick package/settings lookups)
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
