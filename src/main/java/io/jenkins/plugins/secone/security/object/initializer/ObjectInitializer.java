package io.jenkins.plugins.secone.security.object.initializer;

import java.io.File;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClients;
import org.springframework.web.client.RestTemplate;

public abstract class ObjectInitializer {

	private static HttpPost httpPost = new HttpPost();

	private static RestTemplate restTemplate = new RestTemplate();

	private static HttpClient client = HttpClients.custom().build();

	private static MultipartEntityBuilder multipartBodyBuilder = MultipartEntityBuilder.create();

	private static String configPath = ".git" + File.separator + "config";

	public static String getConfigPath() {
		return configPath;
	}

	public static HttpPost getHttpPost() {
		return httpPost;
	}

	public static RestTemplate getRestTemplate() {
		return restTemplate;
	}

	public static HttpClient getClient() {
		return client;
	}

	public static MultipartEntityBuilder getMultipartBodyBuilder() {
		return multipartBodyBuilder;
	}
}
