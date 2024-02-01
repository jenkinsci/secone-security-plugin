package io.jenkins.plugins.secone.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;

import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.cloudbees.plugins.credentials.CredentialsProvider;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.jenkins.plugins.secone.security.object.initializer.ObjectInitializer;
import io.jenkins.plugins.secone.security.pojo.Threshold;
import jenkins.model.Jenkins;

@RunWith(MockitoJUnitRunner.class)
public class SecOneScannerPluginTest {

	@Mock
	private AbstractBuild<?, ?> abstractBuild;

	@Mock
	private Run<?, ?> run;

	@Mock
	private FilePath filePath;

	@Mock
	private Launcher launcher;

	@Mock
	private BuildListener buildListener;

	private final TaskListener taskListener = Mockito.mock(TaskListener.class);

	@Mock
	private EnvVars envVars;

	private SecOneScannerPlugin plugin;

	@Mock
	private Jenkins jenkins;

	@Mock
	private org.apache.http.HttpEntity httpEntity;

	private static MockedStatic<Jenkins> mockedJenkins;
	private static MockedStatic<CredentialsProvider> mockedCredentialsProvider;
	private static MockedStatic<ObjectInitializer> mockedCustomHttpPost;

	private static String WORKSPACE_DIRECTORY_LOCATION;

	private static InputStream sampleReportStream;

	@Before
	public void setUp() throws URISyntaxException, FileNotFoundException {

		WORKSPACE_DIRECTORY_LOCATION = new File("src/test/resources/test-data").getAbsolutePath();

		sampleReportStream = new FileInputStream(WORKSPACE_DIRECTORY_LOCATION + "/sampleapp-report.txt");

		plugin = new SecOneScannerPlugin("customCredentialsId");
		when(taskListener.getLogger()).thenReturn(mock(PrintStream.class));
		mockJenkkins();
		mockedCustomHttpPost = mockStatic(ObjectInitializer.class);
		mock(RestTemplate.class);
	}

	@After
	public void close() {
		mockedJenkins.close();
		mockedCredentialsProvider.close();
		mockedCustomHttpPost.close();
	}

	@Test
	public void testScanFromUI() throws Exception {
		prepareScanSetup();
		assertEquals(true, plugin.perform(abstractBuild, launcher, buildListener));
	}

	@Test
	public void testScanWithThresholdMediumThreshold() throws Exception {
		plugin.setApplyThreshold(true);
		prepareScanSetup();
		// fail build if threshold breached
		Threshold threshold = new Threshold("100", "100", "0", "", "fail");

		plugin.setThreshold(threshold);

		assertThrows(AbortException.class, () -> plugin.perform(abstractBuild, launcher, buildListener));

	}

	@Test(expected = AbortException.class)
	public void testScanWithThresholdWhereStatusActionIsFail() throws Exception {
		plugin.setApplyThreshold(true);
		prepareScanSetup();
		// fail build if threshold breached
		Threshold threshold = new Threshold("0", "10", "", "", "fail");

		plugin.setThreshold(threshold);

		plugin.perform(abstractBuild, launcher, buildListener);

	}

	@Test
	public void testScanWithThresholdWhereStatusActionIsUnstable() throws Exception {
		plugin.setApplyThreshold(true);
		prepareScanSetup();
		// fail build if threshold breached
		Threshold threshold = new Threshold("0", "10", "", "", "unstable");

		plugin.setThreshold(threshold);

		assertEquals(true, plugin.perform(abstractBuild, launcher, buildListener));
	}

	@Test
	public void testScanWithThresholdWhereStatusActionIsContinue() throws Exception {
		plugin.setApplyThreshold(true);
		prepareScanSetup();
		// fail build if threshold breached
		Threshold threshold = new Threshold("0", "10", "", "", "continue");

		plugin.setThreshold(threshold);

		assertEquals(true, plugin.perform(abstractBuild, launcher, buildListener));

	}

	private void prepareScanSetup() throws Exception {

		when(buildListener.getLogger()).thenReturn(System.out);
		when(abstractBuild.getEnvironment(buildListener)).thenReturn(envVars);
		when(envVars.get("SEC1_INSTANCE_URL")).thenReturn("https://api.sec1.io");

		when(envVars.get("WORKSPACE")).thenReturn(WORKSPACE_DIRECTORY_LOCATION);

		StringCredentials apiKeyCred = mock(StringCredentials.class);

		when(CredentialsProvider.findCredentialById(eq("customCredentialsId"), eq(StringCredentials.class),
				eq(abstractBuild), anyList())).thenReturn(apiKeyCred);

		Secret mysecret = mock(Secret.class);

		when(apiKeyCred.getSecret()).thenReturn(mysecret);

		when(apiKeyCred.getSecret().getPlainText()).thenReturn("testApiKey");

		String manifestUrl = envVars.get("SEC1_INSTANCE_URL") + "/rest/foss/supported-manifest";
		String responseBody = "{\"data\": [\"pom.xml\"]}";
		ResponseEntity<String> responseEntity = ResponseEntity.ok(responseBody);
		HttpHeaders headers = new HttpHeaders();
		headers.set("sec1-api-key", "testApiKey");

		RestTemplate restTemplate = mock(RestTemplate.class);
		when(ObjectInitializer.getRestTemplate()).thenReturn(restTemplate);

		when(restTemplate.exchange(eq(manifestUrl), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
				.thenReturn(responseEntity);

		MultipartEntityBuilder multipartBodyBuilder = mock(MultipartEntityBuilder.class);
		when(ObjectInitializer.getMultipartBodyBuilder()).thenReturn(multipartBodyBuilder);

		when(multipartBodyBuilder.build()).thenReturn(httpEntity);

		HttpPost httpPost = mock(HttpPost.class);

		when(ObjectInitializer.getHttpPost()).thenReturn(httpPost);

		HttpResponse httpResponse = mock(HttpResponse.class);

		HttpClient client = mock(HttpClient.class);
		when(ObjectInitializer.getClient()).thenReturn(client);

		when(client.execute(httpPost)).thenReturn(httpResponse);

		StatusLine statusLine = mock(StatusLine.class);
		when(httpResponse.getStatusLine()).thenReturn(statusLine);
		when(statusLine.getStatusCode()).thenReturn(200);

		when(httpResponse.getEntity()).thenReturn(httpEntity);

		when(httpEntity.getContent()).thenReturn(sampleReportStream);

		when(ObjectInitializer.getConfigPath()).thenReturn("config");

	}

	@Test(expected = AbortException.class)
	public void testInvalidScmUrl() throws Exception {
		when(buildListener.getLogger()).thenReturn(System.out);
		when(abstractBuild.getEnvironment(buildListener)).thenReturn(envVars);
		when(envVars.get("SEC1_INSTANCE_URL")).thenReturn("https://api.sec1.io");

		// when(envVars.get("WORKSPACE")).thenReturn("idont/exist");

		StringCredentials apiKeyCred = mock(StringCredentials.class);

		when(CredentialsProvider.findCredentialById(eq("customCredentialsId"), eq(StringCredentials.class),
				eq(abstractBuild), anyList())).thenReturn(apiKeyCred);

		Secret mysecret = mock(Secret.class);

		when(apiKeyCred.getSecret()).thenReturn(mysecret);

		when(apiKeyCred.getSecret().getPlainText()).thenReturn("testApiKey");

		plugin.perform(abstractBuild, launcher, buildListener);
	}

	@Test(expected = AbortException.class)
	public void testScanFromUIException() throws Exception {
		when(buildListener.getLogger()).thenReturn(System.out);
		when(abstractBuild.getEnvironment(buildListener)).thenReturn(envVars);

		when(envVars.get("WORKSPACE")).thenReturn(WORKSPACE_DIRECTORY_LOCATION);
		assertEquals(1, plugin.perform(abstractBuild, launcher, buildListener));
	}

	@Test(expected = AbortException.class)
	public void testPerformFromScriptException() throws Exception {
		plugin.perform(run, filePath, envVars, launcher, taskListener);
	}

	@Test(expected = AbortException.class)
	public void testPerformFromScriptNoScanFileLocation() throws Exception {
		plugin.setScanFileLocation("");
		plugin.perform(run, filePath, envVars, launcher, taskListener);
	}

	@Test
	public void testGetApiKey() throws Exception {

		String apiKey = "testApiKey";

		when(CredentialsProvider.findCredentialById(anyString(), eq(StringCredentials.class), eq(run), anyList()))
				.thenReturn(null);

		mockApiKeyJourney("SEC1_API_KEY");

		assertEquals(apiKey, plugin.getApiKey(run, taskListener));
	}

	private void mockApiKeyJourney(String keyID) {

		StringCredentials apiKeyCred = mock(StringCredentials.class);

		when(CredentialsProvider.findCredentialById(eq(keyID), eq(StringCredentials.class), eq(run), anyList()))
				.thenReturn(apiKeyCred);

		Secret mysecret = mock(Secret.class);

		when(apiKeyCred.getSecret()).thenReturn(mysecret);

		when(apiKeyCred.getSecret().getPlainText()).thenReturn("testApiKey");
	}

	@Test
	public void testGetApiKeyWithCustomCredentialsId() throws Exception {
		String apiKey = "testApiKey";
		plugin.setApiCredentialsId("customCredentialsId");

		mockApiKeyJourney("customCredentialsId");

		assertEquals(apiKey, plugin.getApiKey(run, taskListener));

	}

	@Test
	public void testGetApiKeyWithNoCredentials() throws Exception {
		when(CredentialsProvider.findCredentialById(anyString(), eq(StringCredentials.class), eq(run), anyList()))
				.thenReturn(null);
		assertNull(plugin.getApiKey(run, taskListener));
	}

	private void mockJenkkins() {
		mockedJenkins = mockStatic(Jenkins.class);
		when(Jenkins.get()).thenReturn(jenkins);
		mockedCredentialsProvider = mockStatic(CredentialsProvider.class);
	}
}
