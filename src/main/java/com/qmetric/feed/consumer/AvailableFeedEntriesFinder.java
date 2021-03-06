package com.qmetric.feed.consumer;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.qmetric.feed.consumer.store.FeedTracker;
import com.theoryinpractise.halbuilder.DefaultRepresentationFactory;
import com.theoryinpractise.halbuilder.api.Link;
import com.theoryinpractise.halbuilder.api.ReadableRepresentation;
import com.theoryinpractise.halbuilder.api.RepresentationFactory;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;

import static com.google.common.collect.FluentIterable.from;
import static com.google.common.collect.Iterables.concat;

class AvailableFeedEntriesFinder
{
    private static final Logger log = LoggerFactory.getLogger(AvailableFeedEntriesFinder.class);

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormat.forPattern("dd/MM/yyyy HH:mm:ss");

    private static final String ENTRY_ID = "_id";

    private final FeedEndpoint endpoint;

    private final FeedTracker feedTracker;

    private final Optional<EarliestEntryLimit> earliestEntryLimit;

    private final RepresentationFactory representationFactory;

    private final FeedEndpointFactory feedEndpointFactory;

    AvailableFeedEntriesFinder(final FeedEndpoint endpoint, final FeedEndpointFactory feedEndpointFactory, final FeedTracker feedTracker, final Optional<EarliestEntryLimit> earliestEntryLimit)
    {
        this.endpoint = endpoint;
        this.feedTracker = feedTracker;
        this.earliestEntryLimit = earliestEntryLimit;
        this.representationFactory = new DefaultRepresentationFactory();
        this.feedEndpointFactory = feedEndpointFactory;
    }

    public void trackNewEntries()
    {
        trackAll(newEntries());
    }

    private void trackAll(final List<EntryId> newEntries)
    {
        for (final EntryId entry : newEntries)
        {
            feedTracker.track(entry);
        }
    }

    private List<EntryId> newEntries()
    {
        return from(concat(ImmutableList.copyOf(new UnconsumedPageIterator()))) //
                .toList() //
                .reverse();
    }

    private EntryId idOf(final ReadableRepresentation entry)
    {
        return EntryId.of((String) entry.getValue(ENTRY_ID));
    }

    private class UnconsumedPageIterator implements Iterator<List<EntryId>>
    {
        private static final String NEXT_LINK_RELATION = "next";

        private static final String PUBLISHED = "_published";

        private Optional<ReadableRepresentation> currentPage;

        UnconsumedPageIterator()
        {
            currentPage = Optional.of(representationFactory.readRepresentation(endpoint.get()));
        }

        @Override public boolean hasNext()
        {
            return currentPage.isPresent();
        }

        @Override public void remove()
        {
            throw new UnsupportedOperationException();
        }

        @Override public List<EntryId> next()
        {
            log.info("Reading feed-page {}", currentPage.get().getResourceLink().getHref());

            final List<? extends ReadableRepresentation> allFromPage = currentPageEntries();

            final List<? extends ReadableRepresentation> newEntries = newEntries(allFromPage);

            flipToNextPage(allFromPage, newEntries);

            return from(newEntries).transform(new Function<ReadableRepresentation, EntryId>()
            {
                @Override public EntryId apply(final ReadableRepresentation input)
                {
                    return idOf(input);
                }
            }).toList();
        }

        private List<? extends ReadableRepresentation> currentPageEntries()
        {
            return currentPage.get().getResourcesByRel("entries");
        }

        private void flipToNextPage(final List<? extends ReadableRepresentation> allFromPage, final List<? extends ReadableRepresentation> unconsumedFromPage)
        {
            log.debug("Found {} unconsumed entries out of {}", unconsumedFromPage.size(), allFromPage.size());

            this.currentPage = allFromPage.size() == unconsumedFromPage.size() ? getNextPage() : Optional.<ReadableRepresentation>absent();

            log.debug("Next page: {}", currentPage);
        }

        private Optional<ReadableRepresentation> getNextPage()
        {
            return nextLink(currentPage.get()).transform(new Function<Link, ReadableRepresentation>()
            {
                @Override public ReadableRepresentation apply(final Link link)
                {
                    return representationFactory.readRepresentation(feedEndpointFactory.create(link.getHref()).get());
                }
            });
        }

        private Optional<Link> nextLink(final ReadableRepresentation readableRepresentation)
        {
            return Optional.fromNullable(readableRepresentation.getLinkByRel(NEXT_LINK_RELATION));
        }

        private List<? extends ReadableRepresentation> newEntries(final List<? extends ReadableRepresentation> entries)
        {
            return from(entries).filter(new Predicate<ReadableRepresentation>()
            {
                @Override
                public boolean apply(final ReadableRepresentation input)
                {
                    return hasConsumablePublishedDate(input) && isNotTracked(input);
                }

                private boolean isNotTracked(final ReadableRepresentation entry)
                {
                    return !feedTracker.isTracked(idOf(entry));
                }

                private boolean hasConsumablePublishedDate(final ReadableRepresentation entry)
                {
                    return !earliestEntryLimit.isPresent() || earliestEntryLimit.get().date.isBefore(publishedDate(entry));
                }

                private DateTime publishedDate(final ReadableRepresentation entry)
                {
                    return DATE_FORMATTER.parseDateTime((String) entry.getValue(PUBLISHED));
                }
            }).toList();
        }
    }
}
