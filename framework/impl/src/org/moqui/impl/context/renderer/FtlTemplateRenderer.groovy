/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.context.renderer

import freemarker.template.Template

import org.moqui.context.ExecutionContextFactory
import org.moqui.context.TemplateRenderer
import org.moqui.impl.context.ExecutionContextFactoryImpl

class FtlTemplateRenderer implements TemplateRenderer {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FtlTemplateRenderer.class)

    protected ExecutionContextFactoryImpl ecfi

    FtlTemplateRenderer() { }

    TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf
        return this
    }

    void render(String location, Writer writer) {
        Template theTemplate = ecfi.resourceFacade.getFtlTemplateByLocation(location)
        theTemplate.createProcessingEnvironment(ecfi.executionContext.context, writer).process()
    }

    String stripTemplateExtension(String fileName) {
        return fileName.contains(".ftl") ? fileName.replace(".ftl", "") : fileName
    }

    void destroy() { }
}
