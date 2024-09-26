package br.com.spark.service;


import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Duration;

@ApplicationScoped
public class DockerClientProducer {

    @Inject
    @ConfigProperty(name = "docker.host")
    String dockerHost;

    @Inject
    @ConfigProperty(name = "docker.connect-timeout")
    int connectTimeout;

    @Inject
    @ConfigProperty(name = "docker.response-timeout")
    int responseTimeout;

    @Produces
    @ApplicationScoped
    public DockerClient dockerClient() {
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(URI.create(dockerHost))
                .connectionTimeout(Duration.ofMillis(connectTimeout))
                .responseTimeout(Duration.ofMillis(responseTimeout))
                .build();

        return DockerClientBuilder.getInstance(dockerHost)
                .withDockerHttpClient(httpClient)
                .build();
    }
}
