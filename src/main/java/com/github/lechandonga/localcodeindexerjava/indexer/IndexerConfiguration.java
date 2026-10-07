package com.github.lechandonga.localcodeindexerjava.indexer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IndexerProperties.class)
public class IndexerConfiguration {
}
