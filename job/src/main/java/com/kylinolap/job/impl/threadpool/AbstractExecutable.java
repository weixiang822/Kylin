package com.kylinolap.job.impl.threadpool;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.kylinolap.common.KylinConfig;
import com.kylinolap.common.util.MailService;
import com.kylinolap.job.dao.JobPO;
import com.kylinolap.job.exception.ExecuteException;
import com.kylinolap.job.execution.*;
import com.kylinolap.job.service.ExecutableManager;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Created by qianzhou on 12/16/14.
 */
public abstract class AbstractExecutable implements Executable, Idempotent {

    protected static final String SUBMITTER = "submitter";
    protected static final String NOTIFY_LIST = "notify_list";
    protected static final String START_TIME = "startTime";
    protected static final String END_TIME = "endTime";

    private JobPO job;
    protected static final Logger logger = LoggerFactory.getLogger(AbstractExecutable.class);

    protected static ExecutableManager jobService = ExecutableManager.getInstance(KylinConfig.getInstanceFromEnv());

    public AbstractExecutable() {
        String uuid = UUID.randomUUID().toString();
        this.job = new JobPO();
        this.job.setType(this.getClass().getName());
        this.job.setUuid(uuid);
    }

    protected AbstractExecutable(JobPO job) {
        Preconditions.checkArgument(job != null, "job cannot be null");
        Preconditions.checkArgument(job.getId() != null, "job id cannot be null");
        this.job = job;
    }

    protected void onExecuteStart(ExecutableContext executableContext) {
        Map<String, String> info = Maps.newHashMap();
        info.put(START_TIME, Long.toString(System.currentTimeMillis()));
        jobService.updateJobOutput(getId(), ExecutableState.RUNNING, info, null);
    }

    protected void onExecuteFinished(ExecuteResult result, ExecutableContext executableContext) {
        setEndTime(System.currentTimeMillis());
        if (!isDiscarded()) {
            if (result.succeed()) {
                jobService.updateJobOutput(getId(), ExecutableState.SUCCEED, null, result.output());
            } else {
                jobService.updateJobOutput(getId(), ExecutableState.ERROR, null, result.output());
            }
        } else {
        }
    }

    protected void onExecuteError(Throwable exception, ExecutableContext executableContext) {
        if (!isDiscarded()) {
            jobService.addJobInfo(getId(), END_TIME, Long.toString(System.currentTimeMillis()));
            jobService.updateJobOutput(getId(), ExecutableState.ERROR, null, exception.getLocalizedMessage());
        } else {
        }
    }

    @Override
    public final ExecuteResult execute(ExecutableContext executableContext) throws ExecuteException {
        Preconditions.checkArgument(executableContext instanceof DefaultContext);
        ExecuteResult result;
        try {
            onExecuteStart(executableContext);
            result = doWork(executableContext);
        } catch (Throwable e) {
            onExecuteError(e, executableContext);
            throw new ExecuteException(e);
        }
        onExecuteFinished(result, executableContext);
        return result;
    }

    protected abstract ExecuteResult doWork(ExecutableContext context) throws ExecuteException;

    @Override
    public void cleanup() throws ExecuteException {

    }

    @Override
    public boolean isRunnable() {
        return this.getStatus() == ExecutableState.READY;
    }

    @Override
    public String getName() {
        return job.getName();
    }

    public void setName(String name) {
        job.setName(name);
    }

    @Override
    public final String getId() {
        return job.getId();
    }

    @Override
    public final ExecutableState getStatus() {
        return jobService.getOutput(this.getId()).getState();
    }

    @Override
    public final Map<String, String> getParams() {
        return Collections.unmodifiableMap(job.getParams());
    }

    public final String getParam(String key) {
        return job.getParams().get(key);
    }

    public final void setParam(String key, String value) {
        job.getParams().put(key, value);
    }

    public final long getLastModified() {
        return jobService.getOutput(getId()).getLastModified();
    }

    public final void setSubmitter(String submitter) {
        setParam(SUBMITTER, submitter);
    }

    public final List<String> getNotifyList() {
        final String str = getParam(NOTIFY_LIST);
        if (str != null) {
            return Lists.newArrayList(StringUtils.split(str, ","));
        } else {
            return Collections.emptyList();
        }
    }

    public final void setNotifyList(String notifications) {
        setParam(NOTIFY_LIST, notifications);
    }

    public final void setNotifyList(List<String> notifications) {
        setNotifyList(StringUtils.join(notifications, ","));
    }

    protected Pair<String, String> formatNotifications(ExecutableState state) {
        return null;
    }

    protected final void notifyUserStatusChange(ExecutableState state) {
        try {
            List<String> users = Lists.newArrayList();
            users.addAll(getNotifyList());
            final String adminDls = KylinConfig.getInstanceFromEnv().getAdminDls();
            if (null != adminDls) {
                for (String adminDl : adminDls.split(",")) {
                    users.add(adminDl);
                }
            }
            if (users.isEmpty()) {
                return;
            }
            final Pair<String, String> email = formatNotifications(state);
            if (email == null) {
                return;
            }
            logger.info("prepare to send email to:" + users);
            logger.info("job name:" + getName());
            logger.info("submitter:" + getSubmitter());
            logger.info("notify list:" + users);
            new MailService().sendMail(users, email.getLeft(), email.getRight());
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
        }
    }

    public final String getSubmitter() {
        return getParam(SUBMITTER);
    }

    @Override
    public final Output getOutput() {
        return jobService.getOutput(getId());
    }

    protected long getExtraInfoAsLong(String key, long defaultValue) {
        final String str = jobService.getOutput(getId()).getExtra().get(key);
        if (str != null) {
            return Long.parseLong(str);
        } else {
            return defaultValue;
        }
    }

    protected final void addExtraInfo(String key, String value) {
        jobService.addJobInfo(getId(), key, value);
    }

    public final void setStartTime(long time) {
        addExtraInfo(START_TIME, time + "");
    }

    public final void setEndTime(long time) {
        addExtraInfo(END_TIME, time + "");
    }

    public final long getStartTime() {
        return getExtraInfoAsLong(START_TIME, 0L);
    }

    public final long getEndTime() {
        return getExtraInfoAsLong(END_TIME, 0L);
    }

    public final long getDuration() {
        final long startTime = getStartTime();
        if (startTime == 0) {
            return 0;
        }
        final long endTime = getEndTime();
        if (endTime == 0) {
            return System.currentTimeMillis() - startTime;
        } else {
            return endTime - startTime;
        }
    }

    public JobPO getJobPO() {
        return job;
    }

    /*
    * discarded is triggered by JobService, the Scheduler is not awake of that, so
    *
    * */
    protected final boolean isDiscarded() {
        final ExecutableState status = jobService.getOutput(getId()).getState();
        return status == ExecutableState.DISCARDED;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this).add("id", getId()).add("name", getName()).add("state", getStatus()).toString();
    }
}