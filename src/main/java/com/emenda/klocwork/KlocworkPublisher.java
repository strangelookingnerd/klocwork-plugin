package com.emenda.klocwork;

import com.emenda.klocwork.config.KlocworkPassFailConfig;
import com.emenda.klocwork.config.KlocworkDesktopGateway;
import com.emenda.klocwork.services.KlocworkApiConnection;
import com.emenda.klocwork.util.KlocworkUtil;
import com.emenda.klocwork.util.KlocworkXMLReportParser;

import org.apache.commons.lang3.StringUtils;

import hudson.AbortException;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Proc;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Project;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;

import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.lang.InterruptedException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class KlocworkPublisher extends Publisher {

    private final boolean enableServerGateway;
    private final List<KlocworkPassFailConfig> passFailConfigs;
    private final boolean enableDesktopGateway;
    private final KlocworkDesktopGateway desktopGateway;

    @DataBoundConstructor
    public KlocworkPublisher(boolean enableServerGateway, List<KlocworkPassFailConfig> passFailConfigs,
        boolean enableDesktopGateway, KlocworkDesktopGateway desktopGateway) {
        this.enableServerGateway = enableServerGateway;
        this.passFailConfigs = passFailConfigs;
        this.enableDesktopGateway = enableDesktopGateway;
        this.desktopGateway = desktopGateway;
    }

    public boolean getEnableServerGateway() {
        return enableServerGateway;
    }

    public List<KlocworkPassFailConfig> getPassFailConfigs() {
        return passFailConfigs;
    }

    public boolean getEnableDesktopGateway() {
        return enableDesktopGateway;
    }

    public KlocworkDesktopGateway getDesktopGateway() {
        return desktopGateway;
    }

    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws AbortException {
        KlocworkLogger logger = new KlocworkLogger("Publisher", listener.getLogger());
        EnvVars envVars = null;
        try {
            envVars = build.getEnvironment(listener);
        } catch (IOException | InterruptedException ex) {
            throw new AbortException(ex.getMessage());
        }

        if (enableServerGateway) {
            logger.logMessage("Performing Klocwork Server Gateway");
            for (KlocworkPassFailConfig pfConfig : passFailConfigs) {
                String request = "action=search&project=" + envVars.get(KlocworkConstants.KLOCWORK_PROJECT);
                if (!StringUtils.isEmpty(pfConfig.getQuery())) {
                    try {
                        request += "&query=grouping:off " + URLEncoder.encode(pfConfig.getQuery(), "UTF-8");
                    } catch (UnsupportedEncodingException ex) {
                        throw new AbortException(ex.getMessage());
                    }

                }
                logger.logMessage("Condition Name : " + pfConfig.getConditionName());
                logger.logMessage("Using query: " + request);
                JSONArray response;

                try {
                    String[] ltokenLine = KlocworkUtil.getLtokenValues(envVars, launcher);
                    KlocworkApiConnection kwService = new KlocworkApiConnection(
                                    KlocworkUtil.getAndExpandEnvVar(envVars, KlocworkConstants.KLOCWORK_URL),
                                    ltokenLine[KlocworkConstants.LTOKEN_USER_INDEX],
                                    ltokenLine[KlocworkConstants.LTOKEN_HASH_INDEX]);
                    response = kwService.sendRequest(request);
                } catch (IOException ex) {
                    throw new AbortException("Error: failed to connect to the Klocwork" +
                        " web API.\nCause: " + ex.getMessage());
                }


                logger.logMessage("Number of issues returned : " + Integer.toString(response.size()));
                if (response.size() >= Integer.parseInt(pfConfig.getThreshold())) {
                    logger.logMessage("Threshold exceeded. Marking build as failed.");
                    build.setResult(pfConfig.getResultValue());
                }
                for (int i = 0; i < response.size(); i++) {
                      JSONObject jObj = response.getJSONObject(i);
                      logger.logMessage(jObj.toString());
                }
            }
        }

        if (enableDesktopGateway) {
            KlocworkDesktopBuilder desktopBuilder = (KlocworkDesktopBuilder)
                KlocworkUtil.getInstanceOfBuilder(KlocworkDesktopBuilder.class, build);

            String xmlReport = null;

            if (desktopBuilder == null) {
                throw new AbortException("Could not find build-step for " +
                "Klocwork Desktop analysis in this job. Please configure a " +
                "Klocwork Desktop build.");
            }
            try {
                int totalIssueCount = launcher.getChannel().call(
                    new KlocworkXMLReportParser(
                    build.getWorkspace().getRemote(), xmlReport));
                logger.logMessage("Total Desktop Issues : " +
                    Integer.toString(totalIssueCount));
                logger.logMessage("Configured Threshold : " +
                    desktopGateway.getThreshold());
                if (totalIssueCount >= Integer.parseInt(desktopGateway.getThreshold())) {
                    logger.logMessage("Threshold exceeded. Marking build as failed.");
                        build.setResult(Result.FAILURE);
                }
            } catch (InterruptedException | IOException ex) {
                throw new AbortException(ex.getMessage());
            }
        }


        return true;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            load();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public String getDisplayName() {
            return "Emenda Klocwork Report";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return super.configure(req,formData);
        }
    }
}
