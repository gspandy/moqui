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

import org.moqui.context.TemplateRenderer
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import org.moqui.context.Cache
import org.eclipse.mylyn.wikitext.core.parser.builder.HtmlDocumentBuilder
import org.eclipse.mylyn.wikitext.core.parser.MarkupParser
import org.eclipse.mylyn.wikitext.confluence.core.ConfluenceLanguage
import org.eclipse.mylyn.wikitext.mediawiki.core.MediaWikiLanguage
import org.eclipse.mylyn.wikitext.textile.core.TextileLanguage
import org.eclipse.mylyn.wikitext.tracwiki.core.TracWikiLanguage
import org.eclipse.mylyn.wikitext.twiki.core.TWikiLanguage
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.context.ExecutionContextFactory
import org.moqui.BaseException

class WikiTemplateRenderer implements TemplateRenderer {
    protected final static Logger logger = LoggerFactory.getLogger(WikiTemplateRenderer.class)

    protected ExecutionContextFactoryImpl ecfi

    protected Cache templateWikiLocationCache

    WikiTemplateRenderer() { }

    TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf
        this.templateWikiLocationCache = ecfi.cacheFacade.getCache("resource.wiki.location")
        return this
    }

    void render(String location, Writer writer) {
        String wikiText = templateWikiLocationCache.get(location)
        if (wikiText) {
            writer.write(wikiText)
            return
        }

        StringWriter localWriter = new StringWriter()
        HtmlDocumentBuilder builder = new HtmlDocumentBuilder(localWriter)
        // avoid the <html> and <body> tags
        builder.setEmitAsDocument(false)

        MarkupParser parser
        if (location.endsWith(".cwiki") || location.endsWith(".confluence")) parser = new MarkupParser(new ConfluenceLanguage())
        else if (location.endsWith(".mediawiki")) parser = new MarkupParser(new MediaWikiLanguage())
        else if (location.endsWith(".textile")) parser = new MarkupParser(new TextileLanguage())
        else if (location.endsWith(".tracwiki")) parser = new MarkupParser(new TracWikiLanguage())
        else if (location.endsWith(".twiki")) parser = new MarkupParser(new TWikiLanguage())
        else throw new BaseException("Extension not supported for wiki rendering for location ${location}")

        parser.setBuilder(builder)
        parser.parse(ecfi.resourceFacade.getLocationText(location, false))

        wikiText = localWriter.toString()
        if (wikiText) {
            templateWikiLocationCache.put(location, wikiText)
            writer.write(wikiText)
        }
    }

    String stripTemplateExtension(String fileName) {
        if (fileName.contains(".cwiki")) return fileName.replace(".cwiki", "")
        else if (fileName.contains(".confluence")) return fileName.replace(".confluence", "")
        else if (fileName.contains(".mediawiki")) return fileName.replace(".mediawiki", "")
        else if (fileName.contains(".textile")) return fileName.replace(".textile", "")
        else if (fileName.contains(".tracwiki")) return fileName.replace(".tracwiki", "")
        else if (fileName.contains(".twiki")) return fileName.replace(".twiki", "")
        else return fileName
    }

    void destroy() { }
}
