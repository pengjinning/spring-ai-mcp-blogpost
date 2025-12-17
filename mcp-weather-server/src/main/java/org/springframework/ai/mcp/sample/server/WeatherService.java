/*
* Copyright 2025 - 2025 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.springframework.ai.mcp.sample.server;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.ModelPreferences;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.SamplingMessage;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springaicommunity.mcp.annotation.McpProgressToken;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * @author Christian Tzolov
 */
@Service
public class WeatherService {

	private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

	private final RestClient restClient = RestClient.create();

	/**
	 * The response format from the Open-Meteo API
	 */
	public record WeatherResponse(Current current) {
		public record Current(LocalDateTime time, int interval, double temperature_2m) {
		}
	}

	// https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41&hourly=temperature_2m
	@McpTool(description = "Get the temperature (in celsius) for a specific location")
	public String getTemperature(McpSyncServerExchange exchange,
			@McpToolParam(description = "The location latitude") double latitude,
			@McpToolParam(description = "The location longitude") double longitude,
			@McpProgressToken Object progressToken) {

		log.info("getTemperature called: lat={}, lon={}", latitude, longitude);

		exchange.loggingNotification(LoggingMessageNotification.builder()
			.level(LoggingLevel.DEBUG)
			.data("Call getTemperature Tool with latitude: " + latitude + " and longitude: " + longitude)
			.meta(Map.of()) // non null meata as a workaround for bug: ...
			.build());

		// 0% progress
		exchange.progressNotification(new ProgressNotification(progressToken, 0.0, 1.0, "Retrieving weather forecast"));

		WeatherResponse weatherResponse = restClient.get()
			.uri("https://api.open-meteo.com/v1/forecast?latitude={latitude}&longitude={longitude}&current=temperature_2m",
					latitude, longitude)
			.retrieve()
			.body(WeatherResponse.class);

		log.debug("Weather API response: {}", weatherResponse);

		// Safely derive temperature value to avoid potential NPEs
		Double temperature = (weatherResponse != null && weatherResponse.current() != null)
				? weatherResponse.current().temperature_2m() : null;

		if (temperature == null) {
			log.warn("No temperature data from API for lat={}, lon={}", latitude, longitude);
			exchange.loggingNotification(LoggingMessageNotification.builder()
				.level(LoggingLevel.INFO)
				.data("No temperature data returned from weather API for latitude: " + latitude + ", longitude: "
						+ longitude)
				.meta(Map.of())
				.build());
		}

		String temperatureText = (temperature != null) ? String.valueOf(temperature) : "unknown";
		log.info("Derived temperature: {}", temperatureText);

		String epicPoem = "MCP client doesn't provide sampling capability.";

		if (exchange.getClientCapabilities().sampling() != null) {
			log.info("Client supports sampling; starting sampling.");

			// 50% progress
			exchange.progressNotification(new ProgressNotification(progressToken, 0.5, 1.0, "Start sampling"));

			String samplingMessage = """
					For a weather forecast (temperature is in Celsius): %s.
					At location with latitude: %s and longitude: %s.
					Please write an epic poem about this forecast using a Shakespearean style.
					""".formatted(temperatureText, latitude, longitude);

			log.debug("Sampling prompt: {}", samplingMessage);

			try {
				CreateMessageResult samplingResponse = exchange.createMessage(CreateMessageRequest.builder()
					.systemPrompt("You are a poet!")
					.messages(List.of(new SamplingMessage(Role.USER, new TextContent(samplingMessage))))
					.modelPreferences(ModelPreferences.builder().addHint("zhipuai").build())
					.build());

				if (samplingResponse != null && samplingResponse.content() instanceof TextContent) {
					epicPoem = ((TextContent) samplingResponse.content()).text();
					log.info("Received sampling response successfully.");
				}
			}
			catch (Exception ex) {
				log.warn("Sampling failed; falling back to default text.", ex);
				exchange.loggingNotification(LoggingMessageNotification.builder()
					.level(LoggingLevel.WARNING)
					.data("Sampling failed, falling back to default text: " + ex.getMessage())
					.meta(Map.of())
					.build());
			}

		}

		// 100% progress
		exchange.progressNotification(new ProgressNotification(progressToken, 1.0, 1.0, "Task completed"));
		log.info("getTemperature completed for lat={}, lon={}", latitude, longitude);

		return """
				Weather Poem2: %s
				about the weather: %s°C at location with latitude: %s and longitude: %s
				""".formatted(epicPoem, temperatureText, latitude, longitude);
	}

}