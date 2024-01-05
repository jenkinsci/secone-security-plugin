package io.secone.foss.jenkins.plugin;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.json.JSONObject;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.Cause.UserIdCause;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.util.BuildData;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.secone.foss.jenkins.plugin.pojo.Threshold;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;

public class SecOneScannerPlugin extends Builder implements SimpleBuildStep {

	private static final Logger logger = LoggerFactory.getLogger(SecOneScannerPlugin.class);

	private static final String API_CONTEXT = "/rest/foss";

	private static final String SCAN_API = "/scan";

	private static final String INSTANCE_URL = "SEC1_INSTANCE_URL";

	private static final String API_KEY = "SEC1_API_KEY";

	private static final String API_KEY_HEADER = "sec1-api-key";

	private String scmUrl;
	private String scm;
	private String credentialsId;
	private boolean applyThreshold;

	private String instanceUrl;

	private Threshold threshold;

	private String accessToken;

	@DataBoundConstructor
	public SecOneScannerPlugin(String scmUrl, String scm) {
		this.scmUrl = scmUrl;
		this.scm = scm;
	}

	public String getScmUrl() {
		return scmUrl;
	}

	public String getScm() {
		return scm;
	}

	public String getCredentialsId() {
		return credentialsId;
	}

	@DataBoundSetter
	public void setCredentialsId(String credentialsId) {
		this.credentialsId = Util.fixEmpty(credentialsId);
	}

	public boolean isApplyThreshold() {
		return applyThreshold;
	}

	@DataBoundSetter
	public void setApplyThreshold(boolean applyThreshold) {
		this.applyThreshold = applyThreshold;
	}

	public Threshold getThreshold() {
		return threshold;
	}

	@DataBoundSetter
	public void setThreshold(Threshold threshold) {
		this.threshold = threshold;
	}

	public String getAccessToken() {
		return accessToken;
	}

	@DataBoundSetter
	public void setAccessToken(String accessToken) {
		this.accessToken = accessToken;
	}

	/*
	 * @Override public void perform(Run<?, ?> run, EnvVars env, TaskListener
	 * listener) throws InterruptedException, IOException { //
	 * SimpleBuildStep.super.perform(run, env, listener);
	 * 
	 * String fossInstanceUrl = env.get(INSTANCE_URL);
	 * 
	 * String apiKey = env.get(API_KEY);
	 * 
	 * AbstractProject<?, ?> buildProject = (AbstractProject<?, ?>) run.getParent();
	 * 
	 * performScan(run.getAllActions(), run.getCauses(), buildProject, listener,
	 * fossInstanceUrl, apiKey); }
	 */
	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
			throws IOException, InterruptedException {

		String fossInstanceUrl = getInstanceUrl(build.getEnvironment(listener), listener);

		String apiKey = getApiKey();

		if (threshold != null) {
			applyThreshold = true;
		}
		performScan(build.getAllActions(), build.getCauses(), build.getProject(), listener, fossInstanceUrl, apiKey,
				applyThreshold);
		return true;
	}

	private String getInstanceUrl(EnvVars envVars, TaskListener listener) {
		String instanceUrl = envVars.get(INSTANCE_URL);
		if (StringUtils.isNotBlank(instanceUrl)) {
			listener.getLogger().println("SEC1_INSTANCE_URL : " + instanceUrl);
			return instanceUrl;
		}
		listener.getLogger().println("No SEC1_INSTANCE_URL set. Using default : https://api.sec1.io");
		return "https://api.sec1.io";
	}

	@Override
	public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener)
			throws InterruptedException, IOException {

		String fossInstanceUrl = getInstanceUrl(env, listener);

		String apiKey = getApiKey();
		performScan(run.getAllActions(), run.getCauses(), null, listener, fossInstanceUrl, apiKey, applyThreshold);
	}

	private String getApiKey() {
		StandardCredentials credentials = CredentialsMatchers.firstOrNull(
				CredentialsProvider.lookupCredentials(StandardCredentials.class, Jenkins.getInstanceOrNull(),
						ACL.SYSTEM, Collections.emptyList()),
				CredentialsMatchers.withId(API_KEY));

		if (credentials instanceof BaseStandardCredentials) {
			String apiKey = ((StringCredentials) credentials).getSecret().getPlainText();
			// String apiKey = secret.getPlainText();
			return apiKey;

		}
		return null;
	}

	@Override
	public boolean requiresWorkspace() {
		// return SimpleBuildStep.super.requiresWorkspace();
		return true;
	}

	private void performScan(List<? extends Action> actionList, List<Cause> causes, AbstractProject<?, ?> buildProject,
			TaskListener listener, String fossInstanceUrl, String apiKey, boolean applyThreshold)
			throws AbortException {

		String scanUrl = StringUtils.isBlank(instanceUrl) ? fossInstanceUrl + API_CONTEXT + SCAN_API
				: instanceUrl + API_CONTEXT + SCAN_API;

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (StringUtils.isBlank(apiKey)) {
			throw new AbortException(
					"API Key not configured. Please check your configuration. Add SEC1_API_KEY in credentials if missing.");
		}
		headers.set(API_KEY_HEADER, apiKey);
		List<String> scmUrlList = new ArrayList<>();
		if (StringUtils.isBlank(scmUrl) && !CollectionUtils.isEmpty(actionList)) {
			actionList.forEach(action -> {
				if (action instanceof BuildData) {
					BuildData buildData = (BuildData) action;
					if (!CollectionUtils.isEmpty(buildData.getRemoteUrls())) {
						scmUrlList.addAll(buildData.getRemoteUrls());
					}
				}
			});
			scmUrl = scmUrlList.get(0);
		}

		JSONObject inputParamsMap = new JSONObject();
		inputParamsMap.put("location", scmUrl);

		// Descriptor creds = CredentialsProvider.findById(null, credentialsId);
		StringBuilder userId = new StringBuilder("system");
		StringBuilder appName = new StringBuilder();
		try {
			appName.append(getSubUrl(scmUrl));
		} catch (Exception ex) {
			logger.error("Error - extracting app name from url", ex);
			logger.info("Issue extracting app name from url, setting it to default");
			appName = new StringBuilder(scmUrl);
		}
		inputParamsMap.put("urlType", scm);
		inputParamsMap.put("appName", appName);
		inputParamsMap.put("source", "jenkins");
		// List<?> causes = build.getCauses();
		if (causes != null && causes.size() > 0) {
			causes.forEach(cause -> {
				if (cause instanceof UserIdCause) {
					UserIdCause jenkinsUser = (UserIdCause) cause;
					userId.setLength(0);
					userId.append(jenkinsUser.getUserId());
				}
			});

		}
		//inputParamsMap.put("userId", userId);
		String accessTokenStr = StringUtils.isNotBlank(accessToken)
				? Base64.getEncoder().encodeToString((accessToken).getBytes(Charset.forName("UTF-8")))
				: getCredentialsFromScm(scmUrl, buildProject);
		if (StringUtils.isBlank(accessTokenStr)) {
			accessTokenStr = getCredentials(credentialsId, userId.toString());
		}
		// listener.getLogger().println("cred : " + accessTokenStr);
		if (StringUtils.isNotBlank(accessTokenStr)) {
			inputParamsMap.put("accessToken", accessTokenStr);
		}

		listener.getLogger().println("==================== SEC1 SCAN CONFIG ====================");
		// listener.getLogger().println("Scan Instance Url : " + scanUrl);
		listener.getLogger().println("SCM Url               : " + scmUrl);
		listener.getLogger().println("User                  : " + userId);
		listener.getLogger().println("Threshold Enabled     : " + applyThreshold);
		if (threshold != null && applyThreshold) {
			listener.getLogger()
					.println("Threshold Values      : " + "Critical = " + threshold.getCriticalThreshold() + ","
							+ "High = " + threshold.getHighThreshold() + "," + "Medium = "
							+ threshold.getMediumThreshold() + "," + "Low = " + threshold.getLowThreshold());
		}
		listener.getLogger().println("=====================================================");
		// listener.getLogger().println("Request Params -> " + inputParamsMap);

		RestTemplate rest = new RestTemplate();
		HttpEntity<String> request = new HttpEntity<String>(inputParamsMap.toString(), headers);
		ResponseEntity<String> responseEntity = rest.exchange(scanUrl, HttpMethod.POST, request, String.class);
		if (responseEntity.getStatusCodeValue() == 200) {
			JSONObject responseJson = new JSONObject(responseEntity.getBody());
			if (responseJson.has("cveCountDetails")) {
				// listener.getLogger().println("Scan Result : " +
				// responseJson.getJSONObject("cveCountDetails"));
				int critical = responseJson.optJSONObject("cveCountDetails") != null
						? responseJson.getJSONObject("cveCountDetails").optInt("CRITICAL")
						: 0;
				int high = responseJson.optJSONObject("cveCountDetails") != null
						? responseJson.getJSONObject("cveCountDetails").optInt("HIGH")
						: 0;
				int medium = responseJson.optJSONObject("cveCountDetails") != null
						? responseJson.getJSONObject("cveCountDetails").optInt("MEDIUM")
						: 0;
				int low = responseJson.optJSONObject("cveCountDetails") != null
						? responseJson.getJSONObject("cveCountDetails").optInt("LOW")
						: 0;

				listener.getLogger().println("==================== SEC1 SCAN Result ====================");
				if (StringUtils.isBlank(responseJson.optString("errorMessage"))) {
					listener.getLogger().println("Vulnerabilities       : " + "Critical = " + critical + "," + "High = "
							+ high + "," + "Medium = " + medium + "," + "Low = " + low);
					listener.getLogger()
							.println("RAG Status            : " + responseJson.optString("overallRagStatus"));

					listener.getLogger().println("Report Url            : " + responseJson.optString("reportUrl"));

					listener.getLogger().println("=====================================================");

					if (applyThreshold) {
						try {
							if (critical != 0 && critical > Integer.parseInt(threshold.getCriticalThreshold())) {
								throw new AbortException(
										"Critical Vulnerability Threshold breached. Failing the build.");
							}
							if (high != 0 && high > Integer.parseInt(threshold.getHighThreshold())) {
								throw new AbortException("High Vulnerability Threshold breached. Failing the build.");
							}
							if (medium != 0 && medium > Integer.parseInt(threshold.getMediumThreshold())) {
								throw new AbortException("Medium Vulnerability Threshold breached. Failing the build.");
							}
							if (low != 0 && low > Integer.parseInt(threshold.getLowThreshold())) {
								throw new AbortException("Low Vulnerability Threshold breached.");
							}
						} catch (NumberFormatException ex) {
							listener.error(
									"Check values configured for vulnerability threshold. Only numbers are allowed.");
						} catch (AbortException ex) {
							throw ex;
						}
					}
				} else {
					listener.error("Error Details : " + responseJson.optString("errorMessage"));
				}

			}
		}

	}

	public static String getCredentialsFromScm(String scmUrl, AbstractProject<?, ?> buildProject) {
		String base64EncodedToken = "";
		if (buildProject != null && buildProject.getScm() instanceof GitSCM) {
			GitSCM gitScm = (GitSCM) buildProject.getScm();
			for (UserRemoteConfig userRemoteConfig : gitScm.getUserRemoteConfigs()) {
				if (userRemoteConfig != null && StringUtils.equals(userRemoteConfig.getUrl(), scmUrl)) {
					logger.info("Getting creds for : {}", userRemoteConfig.getCredentialsId());
					String credentialsId = userRemoteConfig.getCredentialsId();
					if (StringUtils.isNotBlank(credentialsId)) {
						StandardCredentials credentials = CredentialsMatchers.firstOrNull(
								CredentialsProvider.lookupCredentials(StandardCredentials.class, buildProject,
										ACL.SYSTEM, Collections.emptyList()),
								CredentialsMatchers.withId(credentialsId));
						if (credentials != null && credentials instanceof UsernamePasswordCredentialsImpl) {
							UsernamePasswordCredentialsImpl usernamePassword = (UsernamePasswordCredentialsImpl) credentials;
							String userName = usernamePassword.getUsername();
							String password = usernamePassword.getPassword().getPlainText();
							base64EncodedToken = getBase64EncodedCreds(userName, password);
						}
					}
				}
			}
		}
		return base64EncodedToken;
	}

	private static String getBase64EncodedCreds(String userName, String password) {
		return Base64.getEncoder().encodeToString((userName + ":" + password).getBytes(Charset.forName("UTF-8")));
	}

	private String getCredentials(String credentialsId, String userId) {
		String base64EncodedToken = "";
		if (StringUtils.isNotBlank(credentialsId)) {
			StandardCredentials creds = CredentialsMatchers
					.firstOrNull(
							CredentialsProvider.lookupCredentials(StandardCredentials.class, Jenkins.get(), ACL.SYSTEM,
									Collections.<DomainRequirement>emptyList()),
							CredentialsMatchers.withId(credentialsId));

			if (creds instanceof UsernamePasswordCredentialsImpl) {
				UsernamePasswordCredentialsImpl usernamePassword = (UsernamePasswordCredentialsImpl) creds;
				String userName = usernamePassword.getUsername();
				String password = usernamePassword.getPassword().getPlainText();
				base64EncodedToken = getBase64EncodedCreds(userName, password);
			}
		}
		return base64EncodedToken;
	}

	@Symbol("sec1")
	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		@DataBoundConstructor
		public DescriptorImpl() {
			super(SecOneScannerPlugin.class);
		}

		public FormValidation doCheckScmUrl(@QueryParameter String value, @QueryParameter String instanceUrl) {
			if (StringUtils.isBlank(value))
				return FormValidation.error("SCM url is empty!!!");
			try {
				new URL(value).toURI();
			} catch (MalformedURLException e) {
				return FormValidation.error("Invalid Url!!!");
			} catch (URISyntaxException e) {
				return FormValidation.error("Invalid Url!!!");
			}
			if (StringUtils.isNotBlank(instanceUrl)) {
				try {
					new URL(instanceUrl).toURI();
				} catch (MalformedURLException e) {
					return FormValidation.error("Invalid Url!!!");
				} catch (URISyntaxException e) {
					return FormValidation.error("Invalid Url!!!");
				}
			}
			return FormValidation.ok();
		}

		public FormValidation doCheckCriticalThreshold(@QueryParameter String value,
				@QueryParameter String highThreshold, @QueryParameter String mediumThreshold,
				@QueryParameter String lowThreshold) {
			try {
				Integer.parseInt(value);
				Integer.parseInt(highThreshold);
				Integer.parseInt(mediumThreshold);
				Integer.parseInt(lowThreshold);
			} catch (Exception ex) {
				return FormValidation.error("Only Numbers allowed!");
			}
			return FormValidation.ok();
		}

		@Inject
		private UserRemoteConfig.DescriptorImpl delegate;

		public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item project, @QueryParameter String scmUrl,
				@QueryParameter String credentialsId) {
			return delegate.doFillCredentialsIdItems(project, scmUrl, credentialsId);
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return "Execute Sec1 FOSS Scanner";
		}
	}

	private String getSubUrl(String scmUrl) throws MalformedURLException {
		URL apiUrl = new URL(scmUrl);

		int subUrlLocation = StringUtils.indexOf(scmUrl, apiUrl.getHost()) + apiUrl.getHost().length() + 1;
		if (apiUrl.getPort() != -1) {
			subUrlLocation = StringUtils.indexOf(scmUrl, apiUrl.getHost()) + apiUrl.getHost().length()
					+ String.valueOf(apiUrl.getPort()).length() + 1;
		}
		return StringUtils.substring(scmUrl, subUrlLocation);
	}
};