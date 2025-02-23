/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.main.download;

import java.util.List;

import org.apache.camel.CamelContext;
import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.camel.impl.engine.DefaultDataFormatResolver;
import org.apache.camel.main.util.SuggestSimilarHelper;
import org.apache.camel.spi.DataFormat;
import org.apache.camel.tooling.model.DataFormatModel;

/**
 * Auto downloaded needed JARs when resolving data formats.
 */
public final class DependencyDownloaderDataFormatResolver extends DefaultDataFormatResolver {

    private final CamelCatalog catalog = new DefaultCamelCatalog();
    private final DependencyDownloader downloader;

    public DependencyDownloaderDataFormatResolver(CamelContext camelContext) {
        this.downloader = camelContext.hasService(DependencyDownloader.class);
    }

    @Override
    public DataFormat createDataFormat(String name, CamelContext context) {
        DataFormatModel model = catalog.dataFormatModel(name);
        if (model != null && !downloader.alreadyOnClasspath(model.getGroupId(), model.getArtifactId(),
                model.getVersion())) {
            downloader.downloadDependency(model.getGroupId(), model.getArtifactId(),
                    model.getVersion());
        }
        DataFormat answer = super.createDataFormat(name, context);
        if (answer == null) {
            List<String> suggestion = SuggestSimilarHelper.didYouMean(catalog.findDataFormatNames(), name);
            if (suggestion != null && !suggestion.isEmpty()) {
                String s = String.join(", ", suggestion);
                throw new IllegalArgumentException("Cannot find dataformat with name: " + name + ". Did you mean: " + s);
            }
        }
        return answer;
    }

}
