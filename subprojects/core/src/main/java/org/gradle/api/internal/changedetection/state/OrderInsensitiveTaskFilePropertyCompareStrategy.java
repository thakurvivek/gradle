/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.changedetection.state;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.MultimapBuilder;
import org.gradle.api.internal.changedetection.rules.ChangeType;
import org.gradle.api.internal.changedetection.rules.FileChange;
import org.gradle.api.internal.changedetection.rules.TaskStateChange;
import org.gradle.api.internal.tasks.cache.TaskCacheKeyBuilder;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

class OrderInsensitiveTaskFilePropertyCompareStrategy implements TaskFilePropertyCompareStrategy.Impl {

    private static final Comparator<Entry<NormalizedFileSnapshot, IncrementalFileSnapshotWithAbsolutePath>> ENTRY_COMPARATOR = new Comparator<Entry<NormalizedFileSnapshot, IncrementalFileSnapshotWithAbsolutePath>>() {
        @Override
        public int compare(Entry<NormalizedFileSnapshot, IncrementalFileSnapshotWithAbsolutePath> o1, Entry<NormalizedFileSnapshot, IncrementalFileSnapshotWithAbsolutePath> o2) {
            return o1.getKey().compareTo(o2.getKey());
        }
    };

    private final boolean includeAdded;

    public OrderInsensitiveTaskFilePropertyCompareStrategy(boolean includeAdded) {
        this.includeAdded = includeAdded;
    }

    @Override
    public Iterator<TaskStateChange> iterateContentChangesSince(final Map<String, NormalizedFileSnapshot> current, Map<String, NormalizedFileSnapshot> previous, final String fileType) {
        final ListMultimap<NormalizedFileSnapshot, IncrementalFileSnapshotWithAbsolutePath> unaccountedForPreviousSnapshots = MultimapBuilder.hashKeys().linkedListValues().build();
        for (Entry<String, NormalizedFileSnapshot> entry : previous.entrySet()) {
            String absolutePath = entry.getKey();
            NormalizedFileSnapshot previousSnapshot = entry.getValue();
            unaccountedForPreviousSnapshots.put(previousSnapshot, new IncrementalFileSnapshotWithAbsolutePath(absolutePath, previousSnapshot.getSnapshot()));
        }
        final Iterator<Entry<String, NormalizedFileSnapshot>> currentEntries = current.entrySet().iterator();
        return new AbstractIterator<TaskStateChange>() {
            private Iterator<Entry<NormalizedFileSnapshot, IncrementalFileSnapshotWithAbsolutePath>> unaccountedForPreviousSnapshotsIterator;
            private final ListMultimap<String, IncrementalFileSnapshotWithAbsolutePath> addedFiles = MultimapBuilder.hashKeys().linkedListValues().build();
            private Iterator<IncrementalFileSnapshotWithAbsolutePath> addedFilesIterator;

            @Override
            protected TaskStateChange computeNext() {
                while (currentEntries.hasNext()) {
                    Entry<String, NormalizedFileSnapshot> entry = currentEntries.next();
                    String currentAbsolutePath = entry.getKey();
                    NormalizedFileSnapshot currentNormalizedSnapshot = entry.getValue();
                    IncrementalFileSnapshot currentSnapshot = currentNormalizedSnapshot.getSnapshot();
                    List<IncrementalFileSnapshotWithAbsolutePath> previousSnapshotsForNormalizedPath = unaccountedForPreviousSnapshots.get(currentNormalizedSnapshot);
                    if (previousSnapshotsForNormalizedPath.isEmpty()) {
                        IncrementalFileSnapshotWithAbsolutePath currentSnapshotWithAbsolutePath = new IncrementalFileSnapshotWithAbsolutePath(currentAbsolutePath, currentSnapshot);
                        addedFiles.put(currentNormalizedSnapshot.getNormalizedPath(), currentSnapshotWithAbsolutePath);
                    } else {
                        IncrementalFileSnapshotWithAbsolutePath previousSnapshotWithAbsolutePath = previousSnapshotsForNormalizedPath.remove(0);
                        IncrementalFileSnapshot previousSnapshot = previousSnapshotWithAbsolutePath.getSnapshot();
                        if (!currentSnapshot.isContentUpToDate(previousSnapshot)) {
                            return new FileChange(currentAbsolutePath, ChangeType.MODIFIED, fileType);
                        }
                    }
                }

                // Create a single iterator to use for all of the still unaccounted files
                if (unaccountedForPreviousSnapshotsIterator == null) {
                    if (unaccountedForPreviousSnapshots.isEmpty()) {
                        unaccountedForPreviousSnapshotsIterator = Iterators.emptyIterator();
                    } else {
                        List<Entry<NormalizedFileSnapshot, IncrementalFileSnapshotWithAbsolutePath>> entries = Lists.newArrayList(unaccountedForPreviousSnapshots.entries());
                        Collections.sort(entries, ENTRY_COMPARATOR);
                        unaccountedForPreviousSnapshotsIterator = entries.iterator();
                    }
                }

                if (unaccountedForPreviousSnapshotsIterator.hasNext()) {
                    Entry<NormalizedFileSnapshot, IncrementalFileSnapshotWithAbsolutePath> unaccountedForPreviousSnapshotEntry = unaccountedForPreviousSnapshotsIterator.next();
                    String normalizedPath = unaccountedForPreviousSnapshotEntry.getKey().getNormalizedPath();
                    List<IncrementalFileSnapshotWithAbsolutePath> addedFilesForNormalizedPath = addedFiles.get(normalizedPath);
                    if (!addedFilesForNormalizedPath.isEmpty()) {
                        // There might be multiple files with the same normalized path, here we choose one of them
                        IncrementalFileSnapshotWithAbsolutePath modifiedSnapshot = addedFilesForNormalizedPath.remove(0);
                        return new FileChange(modifiedSnapshot.getAbsolutePath(), ChangeType.MODIFIED, fileType);
                    } else {
                        IncrementalFileSnapshotWithAbsolutePath removedSnapshot = unaccountedForPreviousSnapshotEntry.getValue();
                        return new FileChange(removedSnapshot.getAbsolutePath(), ChangeType.REMOVED, fileType);
                    }
                }

                if (includeAdded) {
                    // Create a single iterator to use for all of the added files
                    if (addedFilesIterator == null) {
                        addedFilesIterator = addedFiles.values().iterator();
                    }

                    if (addedFilesIterator.hasNext()) {
                        IncrementalFileSnapshotWithAbsolutePath addedFile = addedFilesIterator.next();
                        return new FileChange(addedFile.getAbsolutePath(), ChangeType.ADDED, fileType);
                    }
                }

                return endOfData();
            }
        };
    }

    @Override
    public void appendToCacheKey(TaskCacheKeyBuilder builder, Map<String, NormalizedFileSnapshot> snapshots) {
        List<NormalizedFileSnapshot> normalizedSnapshots = Lists.newArrayList(snapshots.values());
        Collections.sort(normalizedSnapshots);
        for (NormalizedFileSnapshot normalizedSnapshot : normalizedSnapshots) {
            normalizedSnapshot.appendToCacheKey(builder);
        }
    }

    @Override
    public boolean isIncludeAdded() {
        return includeAdded;
    }

    private static class IncrementalFileSnapshotWithAbsolutePath {
        private final String absolutePath;
        private final IncrementalFileSnapshot snapshot;

        public IncrementalFileSnapshotWithAbsolutePath(String absolutePath, IncrementalFileSnapshot snapshot) {
            this.absolutePath = absolutePath;
            this.snapshot = snapshot;
        }

        public String getAbsolutePath() {
            return absolutePath;
        }

        public IncrementalFileSnapshot getSnapshot() {
            return snapshot;
        }

        @Override
        public String toString() {
            return String.format("%s (%s)", getSnapshot(), absolutePath);
        }
    }
}
