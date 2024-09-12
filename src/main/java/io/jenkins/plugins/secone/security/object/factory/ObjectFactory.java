package io.jenkins.plugins.secone.security.object.factory;

import java.io.File;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.web.client.RestTemplate;

public class ObjectFactory {

	public HttpPost createHttpPost(String uri) {
		return new HttpPost(uri);
	}

	public CloseableHttpClient createHttpClient() {
		return HttpClients.custom().build();
	}

	public String getGitFolderConfigPath() {
		return ".git" + File.separator + "config";
	}

	public RestTemplate createRestTemplate() {
		return new RestTemplate();
	}

	public MultipartEntityBuilder createMultipartBodyBuilder() {
		return MultipartEntityBuilder.create();
	}
}
