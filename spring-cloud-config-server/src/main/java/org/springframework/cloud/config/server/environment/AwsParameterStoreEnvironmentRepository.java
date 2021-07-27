/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.config.server.environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathResult;
import com.amazonaws.services.simplesystemsmanagement.model.Parameter;

import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.config.ConfigServerProperties;
import org.springframework.util.StringUtils;

import static org.springframework.cloud.config.server.environment.AwsParameterStoreEnvironmentProperties.DEFAULT_PATH_SEPARATOR;

/**
 * @author Iulian Antohe
 */
public class AwsParameterStoreEnvironmentRepository implements EnvironmentRepository {

	private final AWSSimpleSystemsManagement awsSsmClient;

	private final ConfigServerProperties configServerProperties;

	private final AwsParameterStoreEnvironmentProperties environmentProperties;

	public AwsParameterStoreEnvironmentRepository(AWSSimpleSystemsManagement awsSsmClient,
			ConfigServerProperties configServerProperties,
			AwsParameterStoreEnvironmentProperties environmentProperties) {
		this.awsSsmClient = awsSsmClient;
		this.configServerProperties = configServerProperties;
		this.environmentProperties = environmentProperties;
	}

	@Override
	public Environment findOne(String application, String profile, String label) {
		if (!StringUtils.hasLength(application)) {
			application = configServerProperties.getDefaultApplicationName();
		}

		if (!StringUtils.hasLength(profile)) {
			profile = configServerProperties.getDefaultProfile();
		}

		String[] profiles = StringUtils.commaDelimitedListToStringArray(profile);

		Environment result = new Environment(application, profiles, label, null, null);

		Set<String> paths = buildParameterPaths(application, profiles);
		List<PropertySource> propertySources = getPropertySources(paths);

		result.addAll(propertySources);

		return result;
	}

	private Set<String> buildParameterPaths(String application, String[] profiles) {
		Set<String> result = new LinkedHashSet<>();

		String prefix = environmentProperties.getPrefix();
		String defaultApplication = configServerProperties.getDefaultApplicationName();
		String profileSeparator = environmentProperties.getProfileSeparator();
		String defaultProfile = configServerProperties.getDefaultProfile();

		List<String> orderedProfiles = Stream.concat(Arrays.stream(profiles).filter(p -> !p.equals(defaultProfile)),
				Arrays.stream(new String[] { defaultProfile })).collect(Collectors.toList());

		if (application.equals(defaultApplication)) {
			for (String profile : orderedProfiles) {
				result.add(prefix + DEFAULT_PATH_SEPARATOR + defaultApplication + profileSeparator + profile
						+ DEFAULT_PATH_SEPARATOR);
			}
		}
		else {
			for (String profile : orderedProfiles) {
				result.add(prefix + DEFAULT_PATH_SEPARATOR + application + profileSeparator + profile
						+ DEFAULT_PATH_SEPARATOR);

				result.add(prefix + DEFAULT_PATH_SEPARATOR + defaultApplication + profileSeparator + profile
						+ DEFAULT_PATH_SEPARATOR);
			}

			result.add(prefix + DEFAULT_PATH_SEPARATOR + application + DEFAULT_PATH_SEPARATOR);
		}

		result.add(prefix + DEFAULT_PATH_SEPARATOR + defaultApplication + DEFAULT_PATH_SEPARATOR);

		return result;
	}

	private List<PropertySource> getPropertySources(Set<String> parameterPaths) {
		List<PropertySource> result = new ArrayList<>();

		for (String path : parameterPaths) {
			String name = environmentProperties.getOrigin() + path;
			Map<String, String> source = getPropertiesByParameterPath(path);

			if (!source.isEmpty()) {
				result.add(new PropertySource(name, source));
			}
		}

		Map<String, String> overrides = configServerProperties.getOverrides();

		if (!overrides.isEmpty()) {
			result.add(0, new PropertySource("overrides", overrides));
		}

		return result;
	}

	private Map<String, String> getPropertiesByParameterPath(String path) {
		Map<String, String> result = new HashMap<>();

		GetParametersByPathRequest request = new GetParametersByPathRequest().withPath(path)
				.withRecursive(environmentProperties.isRecursive())
				.withWithDecryption(environmentProperties.isDecryptValues())
				.withMaxResults(environmentProperties.getMaxResults());

		GetParametersByPathResult response = awsSsmClient.getParametersByPath(request);

		if (response != null) {
			addParametersToProperties(path, response.getParameters(), result);

			while (StringUtils.hasLength(response.getNextToken())) {
				response = awsSsmClient.getParametersByPath(request.withNextToken(response.getNextToken()));

				addParametersToProperties(path, response.getParameters(), result);
			}
		}

		return result;
	}

	private void addParametersToProperties(String path, List<Parameter> parameters, Map<String, String> properties) {
		for (Parameter parameter : parameters) {
			String name = StringUtils.delete(parameter.getName(), path).replace(DEFAULT_PATH_SEPARATOR, ".");

			properties.put(name, parameter.getValue());
		}
	}

}
