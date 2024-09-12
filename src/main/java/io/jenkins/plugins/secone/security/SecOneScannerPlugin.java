package io.jenkins.plugins.secone.security;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.cloudbees.plugins.credentials.CredentialsProvider;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Plugin;
import hudson.XmlFile;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import io.jenkins.plugins.secone.security.object.factory.ObjectFactory;
import io.jenkins.plugins.secone.security.pojo.Threshold;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;

public class SecOneScannerPlugin extends Builder implements SimpleBuildStep {

	private static final Logger logger = LoggerFactory.getLogger(SecOneScannerPlugin.class);

	private static final String API_CONTEXT = "/rest/foss";

	private static final String SCAN_API = "/scan/file";

	private static final String INSTANCE_URL = "SEC1_INSTANCE_URL";

	private static final String SUPPORTED_MANIFEST = "/supported-manifest";

	private static final String API_KEY = "SEC1_API_KEY";

	private static final String API_KEY_HEADER = "sec1-api-key";

	private String apiCredentialsId;
	private boolean applyThreshold;

	private String scanFileLocation;

	private String actionOnThresholdBreached;

	private Threshold threshold;

	private boolean printInAnsiColor;

	private ObjectFactory objectFactory;

	@DataBoundConstructor
	public SecOneScannerPlugin(String apiCredentialsId, ObjectFactory objectFactory) {
		this.apiCredentialsId = apiCredentialsId;
		this.objectFactory = objectFactory;
	}

	public String getApiCredentialsId() {
		return apiCredentialsId;
	}

	public void setApiCredentialsId(String apiCredentialsId) {
		this.apiCredentialsId = apiCredentialsId;
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

	public String getActionOnThresholdBreached() {
		return actionOnThresholdBreached;
	}

	@DataBoundSetter
	public void setActionOnThresholdBreached(String actionOnThresholdBreached) {
		this.actionOnThresholdBreached = actionOnThresholdBreached;
	}

	public String getScanFileLocation() {
		return scanFileLocation;
	}

	@DataBoundSetter
	public void setScanFileLocation(String scanFileLocation) {
		this.scanFileLocation = scanFileLocation;
	}

	// from UI
	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws AbortException {
		// printStartMessage(listener);
		if (threshold != null) {
			applyThreshold = true;
		}
		if (objectFactory == null) {
			objectFactory = new ObjectFactory();
		}
		printInAnsiColor = isAnsiColorPluginInstalled(build.getParent());
		String workingDirectory = getGitWorkingDirectory(build, listener);
		int result = performScan(build, listener, applyThreshold, workingDirectory);
		if (result != 0) {
			build.setResult(Result.UNSTABLE);
		}
		printEndMessage(listener);
		return true;
	}

	private void printStartMessage(TaskListener listener) {
		printLogs(listener.getLogger(), "**************Sec1 SCA scan start**************", "g");
	}

	private void printEndMessage(TaskListener listener) {
		printLogs(listener.getLogger(), "**************Sec1 SCA scan end**************", "g");
	}

	private String getInstanceUrl(EnvVars envVars, TaskListener listener) {
		String instanceUrl = envVars.get(INSTANCE_URL);
		if (StringUtils.isNotBlank(instanceUrl)) {
			listener.getLogger().println("SEC1_INSTANCE_URL : " + instanceUrl);
			return instanceUrl;
		}
		// listener.getLogger()
		// .println("No environment variable SEC1_INSTANCE_URL set. Using default :
		// https://api.sec1.io");
		return "https://api.sec1.io";
	}

	// From script
	@Override
	public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener)
			throws InterruptedException, IOException {

		printInAnsiColor = isAnsiColorPluginInstalled(run.getParent());

		if (StringUtils.isBlank(scanFileLocation)) {
			printLogs(listener.getLogger(), "scanFileLocation not configured. Please check your configuration.", "r");
		}

		if (StringUtils.isBlank(actionOnThresholdBreached)) {
			printLogs(listener.getLogger(), "actionOnThresholdBreached is not set. Default action is fail.", "g");
		} else if (StringUtils.equalsIgnoreCase(actionOnThresholdBreached, "fail")
				|| StringUtils.equalsIgnoreCase(actionOnThresholdBreached, "unstable")
				|| StringUtils.equalsIgnoreCase(actionOnThresholdBreached, "continue")) {
			if (threshold != null) {
				getThreshold().setStatusAction(actionOnThresholdBreached);
			}
		}

		int result = performScan(run, listener, applyThreshold, scanFileLocation);
		if (result != 0) {
			run.setResult(Result.UNSTABLE);
		}
		printEndMessage(listener);
	}

	public String getApiKey(Run<?, ?> run, TaskListener listener) {
		if (StringUtils.isNotBlank(apiCredentialsId)) {
			printLogs(listener.getLogger(), "Finding api key for credendials id : " + apiCredentialsId, "g");

			StringCredentials apiKeyCreds = CredentialsProvider.findCredentialById(apiCredentialsId,
					StringCredentials.class, run, Collections.emptyList());

			if (apiKeyCreds == null) {
				printLogs(listener.getLogger(), "Credentials id not found : " + apiCredentialsId, "g");
				printLogs(listener.getLogger(), "Finding api key for default credendials id : " + API_KEY, "g");
				apiKeyCreds = CredentialsProvider.findCredentialById(API_KEY, StringCredentials.class, run,
						Collections.emptyList());
			}
			if (apiKeyCreds != null) {
				String apiKey = apiKeyCreds.getSecret().getPlainText();
				return apiKey;
			}
		} else {
			printLogs(listener.getLogger(), "No Credentials Id confgured, using default credendials id : " + API_KEY,
					"g");
			StringCredentials apiKeyCreds = CredentialsProvider.findCredentialById(API_KEY, StringCredentials.class,
					run, Collections.emptyList());
			if (apiKeyCreds != null) {
				String apiKey = apiKeyCreds.getSecret().getPlainText();
				return apiKey;
			}
		}
		return null;
	}

	@Override
	public boolean requiresWorkspace() {
		// return SimpleBuildStep.super.requiresWorkspace();
		return true;
	}

	private int performScan(Run<?, ?> run, TaskListener listener, boolean applyThreshold, String workingDirectory)
			throws AbortException {

		printStartMessage(listener);

		String sec1ApiKey = getApiKey(run, listener);

		if (StringUtils.isBlank(sec1ApiKey)) {
			throw new AbortException(getErrorMessageInAnsi("API Key not configured. Please check your configuration."));
		}

		StringBuilder fossInstanceUrl = new StringBuilder();
		StringBuilder scmUrl = new StringBuilder();
		try {
			fossInstanceUrl.append(getInstanceUrl(run.getEnvironment(listener), listener));
		} catch (IOException | InterruptedException e) {
			throw new AbortException(getErrorMessageInAnsi("Exception while getting environment variables."));
		}

		try {
			String gitUrl = getGitUrl(workingDirectory);
			if (StringUtils.isBlank(gitUrl)) {
				throw new AbortException(getErrorMessageInAnsi(
						"No valid manifest found in working directory. Please check your configuration."));
			}
			scmUrl.append(gitUrl);
		} catch (IOException e) {
			throw new AbortException(
					getErrorMessageInAnsi("Exception while getting getting scm url from .git folder of workspace."));
		}

		String scanUrl = fossInstanceUrl + API_CONTEXT + SCAN_API;

		StringBuilder appName = new StringBuilder();
		try {
			appName.append(getSubUrl(scmUrl.toString()));
		} catch (Exception ex) {
			logger.error("Error - extracting app name from url", ex);
			logger.info("Issue extracting app name from url, setting it to default");
			appName = new StringBuilder(scmUrl);
		}

		String manifestUrl = fossInstanceUrl + API_CONTEXT + SUPPORTED_MANIFEST;

		List<String> supportedManifestList = getSupportedManifest(manifestUrl, sec1ApiKey, listener);
		int result = 0;
		if (!CollectionUtils.isEmpty(supportedManifestList)) {

			List<File> scanFileList = findFilesInDirectory(workingDirectory, supportedManifestList);
			listener.getLogger().println("Files to be scanned : " + scanFileList);
			if (CollectionUtils.isEmpty(scanFileList)) {
				throw new AbortException(
						getErrorMessageInAnsi("No supported manifest found. Supported manifest list : ")
								+ supportedManifestList);
			}

			JSONObject inputParamsMap = new JSONObject();
			inputParamsMap.put("location", scmUrl);
			inputParamsMap.put("appName", appName);
			inputParamsMap.put("source", "jenkins");
			inputParamsMap.put("dirScan", true);

			listener.getLogger().println("==================== SEC1 SCA SCAN CONFIG ====================");
			listener.getLogger().println("SCM Url                " + scmUrl);
			listener.getLogger().println("Threshold Enabled      " + applyThreshold);
			if (threshold != null && applyThreshold) {
				listener.getLogger().println("Threshold Values       " + "Critical "
						+ (StringUtils.isNotBlank(threshold.getCriticalThreshold()) ? threshold.getCriticalThreshold()
								: "NA")
						+ "," + " High "
						+ (StringUtils.isNotBlank(threshold.getHighThreshold()) ? threshold.getHighThreshold() : "NA")
						+ "," + " Medium "
						+ (StringUtils.isNotBlank(threshold.getMediumThreshold()) ? threshold.getMediumThreshold()
								: "NA")
						+ "," + " Low "
						+ (StringUtils.isNotBlank(threshold.getLowThreshold()) ? threshold.getLowThreshold() : "NA"));
			}

			HttpResponse responseEntity = scanFiles(scanUrl, scanFileList, inputParamsMap.toString(), sec1ApiKey);
			if (responseEntity != null && responseEntity.getStatusLine().getStatusCode() == 200) {
				try {
					if (responseEntity.getEntity() != null) {
						org.apache.http.HttpEntity httpScanResponseEntity = responseEntity.getEntity();
						if (httpScanResponseEntity.getContent() != null) {
							InputStream rawContent = httpScanResponseEntity.getContent();
							byte[] bytes = IOUtils.toByteArray(rawContent);
							String content = new String(bytes, Charset.defaultCharset().name());
							JSONObject responseJson = new JSONObject(content);
							if (responseJson.has("cveCountDetails")) {
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

								listener.getLogger()
										.println("==================== SEC1 SCA SCAN RESULT ====================");
								if (StringUtils.isBlank(responseJson.optString("errorMessage"))) {
									listener.getLogger().println("Vulnerabilities Found  " + "Critical " + critical
											+ "," + " High " + high + "," + " Medium " + medium + "," + " Low " + low);
									listener.getLogger().println(
											"RAG Status             " + responseJson.optString("overallRagStatus"));
									listener.getLogger()
											.println("Report Url             " + responseJson.optString("reportUrl"));

									// listener.getLogger().println("=====================================================");

									if (applyThreshold) {

										if (critical != 0 && threshold.getCriticalThreshold() != null
												&& NumberUtils.isDigits(threshold.getCriticalThreshold())
												&& critical >= Integer.parseInt(threshold.getCriticalThreshold())) {
											String message = "Critical Vulnerability Threshold breached.";
											result = failBuildOnThresholdBreach(message, listener, threshold);
										}
										if (high != 0 && threshold.getHighThreshold() != null
												&& NumberUtils.isDigits(threshold.getHighThreshold())
												&& high >= Integer.parseInt(threshold.getHighThreshold())) {
											String message = "High Vulnerability Threshold breached.";
											result = failBuildOnThresholdBreach(message, listener, threshold);
										}
										if (medium != 0 && threshold.getMediumThreshold() != null
												&& NumberUtils.isDigits(threshold.getMediumThreshold())
												&& medium >= Integer.parseInt(threshold.getMediumThreshold())) {
											String message = "Medium Vulnerability Threshold breached.";
											result = failBuildOnThresholdBreach(message, listener, threshold);
										}
										if (low != 0 && threshold.getLowThreshold() != null
												&& NumberUtils.isDigits(threshold.getLowThreshold())
												&& low >= Integer.parseInt(threshold.getLowThreshold())) {
											String message = "Low Vulnerability Threshold breached.";
											result = failBuildOnThresholdBreach(message, listener, threshold);
										}
									}
								} else {
									printLogs(listener.getLogger(),
											"Error Details : " + responseJson.optString("errorMessage"), "r");
									result = 2;
								}
							}
						} else {
							logger.info("Invalid content recevied");
							throw new AbortException(
									getErrorMessageInAnsi("Error while processing scan result. Failing the build."));
						}
					}
				} catch (IOException ex) {
					// throw new AbortException(ex.getMessage());
					throw new AbortException(getErrorMessageInAnsi(
							"Attention: Build Failed because of vulnerability threshold level breached."));
				}
			} else {
				logger.error("Issue while getting response from system.");
				throw new AbortException(
						getErrorMessageInAnsi("Error while processing scan result. Failing the build."));
			}
		} else {
			throw new AbortException(
					getErrorMessageInAnsi("No supported manifest list found. Check you connectivity with Sec1 Api : ")
							+ fossInstanceUrl);
		}
		return result;
	}

	private void printLogs(PrintStream logger, String message, String color) {
		if (printInAnsiColor) {
			if (StringUtils.isNotBlank(color)) {
				switch (color) {
				case "g":
					logger.println("\u001B[32m" + message + "\u001B[0m");
					break;
				case "r":
					logger.println("\u001B[31m" + message + "\u001B[0m");
					break;
				default:
					logger.println(message);
					break;
				}
			} else {
				logger.println(message);
			}
		} else {
			logger.println(message);
		}
	}

	private List<String> getSupportedManifest(String apiUrl, String apiKey, TaskListener listener)
			throws AbortException {
		HttpHeaders headers = new HttpHeaders();
		headers.set("sec1-api-key", apiKey);

		HttpEntity<?> entity = new HttpEntity<>(headers);

		try {
			RestTemplate restTemplate = objectFactory.createRestTemplate();
			ResponseEntity<String> manifestResponseEntity = restTemplate.exchange(apiUrl, HttpMethod.GET, entity,
					String.class);
			JSONObject responseJson = new JSONObject(manifestResponseEntity.getBody());
			return parseJsonToDataList(responseJson);
		} catch (HttpClientErrorException e) {
			throw new AbortException(getErrorMessageInAnsi(e.getResponseBodyAsString()));
		} catch (Exception ex) {
			listener.error("" + ex);
			throw new AbortException(getErrorMessageInAnsi("Error while scanning the application. Failing the build."));
		}
	}

	private String getErrorMessageInAnsi(String message) {
		if (printInAnsiColor) {
			return "\u001B[31m" + message + "\u001B[0m";
		}
		return message;
	}

	private List<String> parseJsonToDataList(JSONObject jsonObject) {
		List<String> dataList = new ArrayList<>();
		if (jsonObject.has("data")) {
			JSONArray dataArray = jsonObject.getJSONArray("data");
			for (int i = 0; i < dataArray.length(); i++) {
				dataList.add(dataArray.getString(i));
			}
		}
		return dataList;
	}

	private int failBuildOnThresholdBreach(String message, TaskListener listener, Threshold threshold)
			throws AbortException {
		if (StringUtils.isNotBlank(threshold.getStatusAction())) {
			if (StringUtils.equalsIgnoreCase(threshold.getStatusAction(), "fail")) {
				throw new AbortException(message + " Failing the build.");
			} else if (StringUtils.equalsIgnoreCase(threshold.getStatusAction(), "unstable")) {
				printLogs(listener.getLogger(), message, "r");
				return 2;
			} else {
				listener.getLogger().println(message);
			}
		} else {
			throw new AbortException(message + " Failing the build.");
		}
		return 0;
	}

	@Symbol("sec1Security")
	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		@DataBoundConstructor
		public DescriptorImpl() {
			super(SecOneScannerPlugin.class);
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return "Execute Sec1 Security Scan";
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

	private String getGitWorkingDirectory(AbstractBuild<?, ?> build, TaskListener listener) throws AbortException {
		try {
			EnvVars envVars = build.getEnvironment(listener);
			return envVars.get("WORKSPACE");
		} catch (IOException | InterruptedException e) {
			throw new AbortException(getErrorMessageInAnsi("Issue while accessing workspace. Failing the build."));
		}
	}

	private HttpResponse scanFiles(String apiUrl, List<File> fileList, String requestParameter, String sec1ApiKey) {

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		headers.set(API_KEY_HEADER, sec1ApiKey);

		MultipartEntityBuilder multipartBodyBuilder = objectFactory.createMultipartBodyBuilder();

		multipartBodyBuilder.addTextBody("request", requestParameter);

		for (File file : fileList) {
			multipartBodyBuilder.addBinaryBody("file", file);
		}

		org.apache.http.HttpEntity multipartBody = multipartBodyBuilder.build();
		HttpPost httpPost = objectFactory.createHttpPost(apiUrl);
		httpPost.addHeader(API_KEY_HEADER, sec1ApiKey);
		httpPost.setEntity(multipartBody);
		CloseableHttpClient client = objectFactory.createHttpClient();
		try {
			HttpResponse response = client.execute(httpPost);
			return response;
		} catch (IOException e) {
			logger.error("Issue while connecting to api.", e);
		}
		return null;
	}

	private List<File> findFilesInDirectory(String directoryPath, List<String> targetFileNames) {
		List<File> matchingFiles = new ArrayList<>();

		File directory = new File(directoryPath);
		File[] files = directory.listFiles();

		if (files != null) {
			for (File file : files) {
				if (file.isFile() && targetFileNames.contains(file.getName())) {
					matchingFiles.add(file);
				}
			}
		}
		return matchingFiles;
	}

	public String getGitUrl(String repositoryPath) throws IOException {
		String gitConfigPath = repositoryPath + File.separator + objectFactory.getGitFolderConfigPath();

		/*
		 * if (!Path.of(gitConfigPath).toFile().exists()) { return null; }
		 */

		try (BufferedReader reader = new BufferedReader(new FileReader(gitConfigPath, StandardCharsets.UTF_8))) {
			String line;
			boolean inRemoteSection = false;

			while ((line = reader.readLine()) != null) {
				if (line.trim().equals("[remote \"origin\"]")) {
					inRemoteSection = true;
				}
				if (inRemoteSection && line.trim().startsWith("url")) {
					String[] parts = line.split("=");
					if (parts.length == 2) {
						String rawUrl = parts[1].trim();
						return removeCredentialsFromGitUrl(rawUrl);
					}
				}
				if (inRemoteSection && line.trim().startsWith("[") && !line.trim().equals("[remote \"origin\"]")) {
					break;
				}
			}
		} catch (IOException e) {
			throw e;
		}
		return null;
	}

	public String getGitFolderConfigPath() {
		return ".git" + File.separator + "config";
	}

	private String removeCredentialsFromGitUrl(String rawUrl) {
		try {
			URI uri = new URI(rawUrl);
			String userInfo = uri.getUserInfo();

			if (userInfo != null) {
				return rawUrl.replace(userInfo + "@", "");
			}
		} catch (URISyntaxException e) {
			e.printStackTrace();
		}

		return rawUrl;
	}

	private boolean isAnsiColorPluginInstalled(Job<?, ?> job) {
		Plugin plugin = Jenkins.get().getPlugin("ansicolor");
		if (plugin != null && plugin.getWrapper().isEnabled()) {
			XmlFile config = job.getConfigFile();
			try {
				String configString = config.asString();
				if (StringUtils.isNotBlank(configString)
						&& StringUtils.contains(configString, "hudson.plugins.ansicolor.AnsiColorBuildWrapper")) {
					return true;
				}
			} catch (Exception ex) {
				return false;
			}
		}
		return false;
	}
}