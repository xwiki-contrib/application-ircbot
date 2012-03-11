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

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.axis.utils.StringUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.ircbot.IRCBot;
import org.xwiki.ircbot.IRCBotException;
import org.xwiki.ircbot.wiki.IRCEventListenerConfiguration;
import org.xwiki.ircbot.wiki.WikiIRCModel;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Send notifications to an IRC channel when a document is modified in the wiki.
 *
 * @version $Id$
 * @since 4.0M1
 */
@Component
@Named("ircdocumentevents")
@Singleton
public class IRCEventListener implements EventListener
{
    @Inject
    private IRCBot bot;

    @Inject
    private IRCEventListenerConfiguration configuration;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    /**
     * The logger to log.
     */
    @Inject
    private Logger logger;

    @Inject
    private WikiIRCModel ircModel;

    @Override
    public List<Event> getEvents()
    {
        return Arrays.<Event>asList(new DocumentUpdatedEvent(), new DocumentDeletedEvent(), new DocumentCreatedEvent());
    }

    @Override
    public String getName()
    {
        return "ircdocumentevents";
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        // Only send an event if the bot is connected and the Event has a source being a XWikiDocument.
        if (this.bot.isConnected() && source instanceof XWikiDocument) {
            XWikiDocument document = (XWikiDocument) source;
            DocumentReference reference = document.getDocumentReference();
            String referenceAsString = this.serializer.serialize(reference);
            boolean shouldSendNotification = true;

            try {
                // Verify if we should send this notification (i.e. that it's not in the exclusion list).
                // For example we may not want to send notifications when the Log Listener modifies an IRC Archive
                // since that would cause an infinite loop...
                for (Pattern pattern : this.configuration.getExclusionPatterns()) {
                    if (pattern.matcher(referenceAsString).matches()) {
                        shouldSendNotification = false;
                        break;
                    }
                }

                // Send notification to the IRC channel if we're allowed.
                if (shouldSendNotification) {
                    String message = String.format("%s was modified by %s %s - %s",
                        referenceAsString,
                        getNotificationAuthor(event, document),
                        getNotificationComment(event, document),
                        getNotificationURL(event, document));
                    this.bot.sendMessage(this.bot.getChannelsNames().iterator().next(), message);
                }
            } catch (IRCBotException e) {
                // Failed to handle the event, log an error
                this.logger.error("Failed to send IRC notification for document [{}]",
                    this.serializer.serialize(reference), e);
            }
        }
    }

    private String getNotificationAuthor(Event event, XWikiDocument source) throws IRCBotException
    {
        DocumentReference authorReference;

        // If the document has been deleted then the author is the author who's done the delete (i.e. the current
        // author) and not the author of the document.
        if (event instanceof  DocumentDeletedEvent) {
            authorReference = this.ircModel.getXWikiContext().getUserReference();
        } else {
            authorReference = source.getAuthorReference();
        }

        return this.serializer.serialize(authorReference);
    }

    private String getNotificationComment(Event event, XWikiDocument source)
    {
        String comment;
        if (event instanceof  DocumentDeletedEvent) {
            comment = "(deleted)";
        } else if (event instanceof  DocumentCreatedEvent) {
            comment = "(created)";
            if (!StringUtils.isEmpty(source.getComment())) {
                comment = comment + " " + source.getComment();
            }
        } else {
            comment = source.getComment();
        }

        return comment;
    }

    private String getNotificationURL(Event event, XWikiDocument source) throws IRCBotException
    {
        String url;
        if (event instanceof DocumentCreatedEvent || event instanceof DocumentDeletedEvent) {
            url = source.getExternalURL("view", this.ircModel.getXWikiContext());
        } else {
            // Return a diff URL since the action done was a modification
            url = source.getExternalURL("view",
                String.format("viewer=changes&amp;rev2=%s", source.getVersion()), this.ircModel.getXWikiContext());
        }

        return url;
    }
}