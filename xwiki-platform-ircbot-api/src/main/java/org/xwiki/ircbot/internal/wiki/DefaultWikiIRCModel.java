/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.ircbot.internal.wiki;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.ircbot.IRCBotException;
import org.xwiki.ircbot.wiki.WikiIRCBotConstants;
import org.xwiki.ircbot.wiki.WikiIRCModel;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Default implementation of {@link org.xwiki.ircbot.wiki.WikiIRCModel}.
 *
 * @version $Id$
 * @since 4.0M1
 */
@Component
@Singleton
public class DefaultWikiIRCModel implements WikiIRCModel, WikiIRCBotConstants
{
    /**
     * The {@link org.xwiki.context.Execution} component used for accessing XWikiContext.
     */
    @Inject
    private Execution execution;

    @Inject
    private EntityReferenceSerializer<String> defaultSerializer;

    @Inject
    @Named("compactwiki")
    private EntityReferenceSerializer<String> compactWikiSerializer;

    /**
     * Used to perform search for IRC Bot listener classes in the current wiki.
     */
    @Inject
    private QueryManager queryManager;

    @Override
    public XWikiDocument getDocument(DocumentReference reference) throws IRCBotException
    {
        XWikiDocument doc;
        XWikiContext xwikiContext = getXWikiContext();
        try {
            doc = xwikiContext.getWiki().getDocument(reference, xwikiContext);
        } catch (XWikiException e) {
            throw new IRCBotException(String.format("Unable to load document [%s]",
                this.defaultSerializer.serialize(reference)), e);
        }
        return doc;
    }

    @Override
    public XWikiDocument getConfigurationDocument() throws IRCBotException
    {
        return getDocument(new DocumentReference(getXWikiContext().getDatabase(), SPACE, CONFIGURATION_PAGE));
    }

    @Override
    public XWikiContext getXWikiContext() throws IRCBotException
    {
        XWikiContext xwikiContext = (XWikiContext) this.execution.getContext().getProperty("xwikicontext");
        if (xwikiContext == null) {
            throw new IRCBotException("The XWiki Context is not available in the Execution Contexte");
        }
        return xwikiContext;
    }

    @Override
    public BotData loadBotData() throws IRCBotException
    {
        XWikiDocument configurationDocument = getConfigurationDocument();
        BaseObject configurationObject = configurationDocument.getXObject(WIKI_BOT_CONFIGURATION_CLASS);
        if (configurationObject == null) {
            // There's no Bot Configuration object
            throw new IRCBotException(String.format("Cannot find IRC Bot Configuration object in [%s] document",
                this.compactWikiSerializer.serialize(configurationDocument.getDocumentReference())));
        }

        BotData botData = new BotData(
            configurationObject.getStringValue(BOTNAME_PROPERTY),
            configurationObject.getStringValue(SERVER_PROPERTY),
            configurationObject.getStringValue(PASSWORD_PROPERTY),
            configurationObject.getStringValue(CHANNEL_PROPERTY),
            configurationObject.getIntValue(INACTIVE_PROPERTY) != 1);

        return botData;
    }

    @Override
    public List<BotListenerData> getWikiBotListenerData() throws IRCBotException
    {
        List<Object[]> results;
        try {
            Query query = this.queryManager.createQuery(
                String.format("select distinct doc.space, doc.name, listener.name, listener.description "
                    + "from Document doc, doc.object(%s) as listener",
                        this.defaultSerializer.serialize(WIKI_BOT_LISTENER_CLASS)), Query.XWQL);
            results = query.execute();
        } catch (QueryException e) {
            throw new IRCBotException("Failed to locate IRC Bot listener objects in the wiki", e);
        }

        List<BotListenerData> data = new ArrayList<BotListenerData>();
        for (Object[] documentData : results) {
            EntityReference relativeReference = new EntityReference((String) documentData[1], EntityType.DOCUMENT,
                new EntityReference((String) documentData[0], EntityType.SPACE));
            data.add(new BotListenerData(this.compactWikiSerializer.serialize(relativeReference),
                (String) documentData[2], (String) documentData[3], true));
        }

        return data;
    }
}