/*
 * Autopsy Forensic Browser
 *
 * Copyright 2013-18 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.imagegallery.datamodel.grouping;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static java.util.Objects.isNull;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.swing.SortOrder;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;
import org.apache.commons.lang3.ObjectUtils;
import static org.apache.commons.lang3.ObjectUtils.notEqual;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.netbeans.api.progress.ProgressHandle;
import org.openide.util.Exceptions;
import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.events.ContentTagAddedEvent;
import org.sleuthkit.autopsy.casemodule.events.ContentTagDeletedEvent;
import org.sleuthkit.autopsy.coreutils.LoggedTask;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.datamodel.DhsImageCategory;
import org.sleuthkit.autopsy.imagegallery.ImageGalleryController;
import org.sleuthkit.autopsy.imagegallery.datamodel.CategoryManager;
import org.sleuthkit.autopsy.imagegallery.datamodel.DrawableAttribute;
import org.sleuthkit.autopsy.imagegallery.datamodel.DrawableDB;
import org.sleuthkit.autopsy.imagegallery.datamodel.DrawableFile;
import org.sleuthkit.autopsy.imagegallery.datamodel.DrawableTagsManager;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.ContentTag;
import org.sleuthkit.datamodel.DataSource;
import org.sleuthkit.datamodel.SleuthkitCase;
import org.sleuthkit.datamodel.TagName;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData.DbType;

/**
 * Provides an abstraction layer on top of DrawableDB ( and to some extent
 * SleuthkitCase ) to facilitate creation, retrieval, updating, and sorting of
 * DrawableGroups.
 */
public class GroupManager {

    private static final Logger logger = Logger.getLogger(GroupManager.class.getName());

    /** An executor to submit async UI related background tasks to. */
    private final ListeningExecutorService exec = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor(
            new BasicThreadFactory.Builder().namingPattern("GUI Task -%d").build())); //NON-NLS

    private final ImageGalleryController controller;

    @GuardedBy("this") //NOPMD
    boolean regrouping;

    /** list of all analyzed groups */
    @GuardedBy("this") //NOPMD
    private final ObservableList<DrawableGroup> analyzedGroups = FXCollections.observableArrayList();
    private final ObservableList<DrawableGroup> unmodifiableAnalyzedGroups = FXCollections.unmodifiableObservableList(analyzedGroups);

    /** list of unseen groups */
    @GuardedBy("this") //NOPMD
    private final ObservableList<DrawableGroup> unSeenGroups = FXCollections.observableArrayList();
    private final ObservableList<DrawableGroup> unmodifiableUnSeenGroups = FXCollections.unmodifiableObservableList(unSeenGroups);
    /**
     * map from GroupKey} to DrawableGroupSs. All groups (even not fully
     * analyzed or not visible groups could be in this map
     */
    @GuardedBy("this") //NOPMD
    private final Map<GroupKey<?>, DrawableGroup> groupMap = new HashMap<>();

    @GuardedBy("this") //NOPMD
    private ReGroupTask<?> groupByTask;

    /*
     * --- current grouping/sorting attributes ---
     */
    @GuardedBy("this") //NOPMD
    private final ReadOnlyObjectWrapper< GroupSortBy> sortByProp = new ReadOnlyObjectWrapper<>(GroupSortBy.PRIORITY);
    private final ReadOnlyObjectWrapper< DrawableAttribute<?>> groupByProp = new ReadOnlyObjectWrapper<>(DrawableAttribute.PATH);
    private final ReadOnlyObjectWrapper<SortOrder> sortOrderProp = new ReadOnlyObjectWrapper<>(SortOrder.ASCENDING);
    private final ReadOnlyObjectWrapper<DataSource> dataSourceProp = new ReadOnlyObjectWrapper<>(null);//null indicates all datasources

    private final ReadOnlyDoubleWrapper regroupProgress = new ReadOnlyDoubleWrapper();

    @SuppressWarnings("ReturnOfCollectionOrArrayField")
    public ObservableList<DrawableGroup> getAnalyzedGroups() {
        return unmodifiableAnalyzedGroups;
    }

    @SuppressWarnings("ReturnOfCollectionOrArrayField")
    public ObservableList<DrawableGroup> getUnSeenGroups() {
        return unmodifiableUnSeenGroups;
    }

    /**
     * construct a group manager hooked up to the given db and controller
     *
     * @param controller
     */
    public GroupManager(ImageGalleryController controller) {
        this.controller = controller;
    }

    /**
     * Using the current groupBy set for this manager, find groupkeys for all
     * the groups the given file is a part of
     *
     * @param file
     *
     *
     * @return A a set of GroupKeys representing the group(s) the given file is
     *         a part of.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    synchronized public Set<GroupKey<?>> getGroupKeysForFile(DrawableFile file) {
        Set<GroupKey<?>> resultSet = new HashSet<>();
        for (Comparable<?> val : getGroupBy().getValue(file)) {
            if (getGroupBy() == DrawableAttribute.TAGS) {
                if (CategoryManager.isNotCategoryTagName((TagName) val)) {
                    resultSet.add(new GroupKey(getGroupBy(), val, getDataSource()));
                }
            } else {
                resultSet.add(new GroupKey(getGroupBy(), val, getDataSource()));
            }
        }
        return resultSet;
    }

    /**
     * Using the current grouping paramaters set for this manager, find
     * GroupKeys for all the Groups the given file is a part of.
     *
     * @param fileID The Id of the file to get group keys for.
     *
     * @return A set of GroupKeys representing the group(s) the given file is a
     *         part of
     */
    synchronized public Set<GroupKey<?>> getGroupKeysForFileID(Long fileID) {
        try {
            DrawableFile file = getDrawableDB().getFileFromID(fileID);
            return getGroupKeysForFile(file);
        } catch (TskCoreException ex) {
            Logger.getLogger(GroupManager.class.getName()).log(Level.SEVERE, "failed to load file with id: " + fileID + " from database", ex); //NON-NLS
        }
        return Collections.emptySet();
    }

    /**
     * @param groupKey
     *
     * @return return the DrawableGroup (if it exists) for the given GroupKey,
     *         or null if no group exists for that key.
     */
    @Nullable
    synchronized public DrawableGroup getGroupForKey(@Nonnull GroupKey<?> groupKey) {
        return groupMap.get(groupKey);
    }

    synchronized public void reset() {
        if (groupByTask != null) {
            groupByTask.cancel(true);
            regrouping = false;
        }
        setSortBy(GroupSortBy.GROUP_BY_VALUE);
        setGroupBy(DrawableAttribute.PATH);
        setSortOrder(SortOrder.ASCENDING);
        setDataSource(null);

        unSeenGroups.forEach(controller.getCategoryManager()::unregisterListener);
        unSeenGroups.clear();
        analyzedGroups.forEach(controller.getCategoryManager()::unregisterListener);
        analyzedGroups.clear();

        groupMap.values().forEach(controller.getCategoryManager()::unregisterListener);
        groupMap.clear();
    }

    synchronized public boolean isRegrouping() {
        return regrouping;
    }

    /**
     * 'Save' the given group as seen in the drawable db.
     *
     * @param group The DrawableGroup to mark as seen.
     * @param seen  The seen state to set for the given group.
     *
     * @return A ListenableFuture that encapsulates saving the seen state to the
     *         DB.
     */
    public ListenableFuture<?> setGroupSeen(DrawableGroup group, boolean seen) {
        return exec.submit(() -> {
            try {
                getDrawableDB().setGroupSeen(group.getGroupKey(), seen);
                group.setSeen(seen);
                updateUnSeenGroups(group, seen);
            } catch (TskCoreException ex) {
                logger.log(Level.SEVERE, "Error marking group as seen", ex); //NON-NLS
            }
        });
    }

    synchronized private void updateUnSeenGroups(DrawableGroup group, boolean seen) {
        if (seen) {
            unSeenGroups.removeAll(group);
        } else if (unSeenGroups.contains(group) == false) {
            unSeenGroups.add(group);
        }
        sortUnseenGroups();
    }

    /**
     * remove the given file from the group with the given key. If the group
     * doesn't exist or doesn't already contain this file, this method is a
     * no-op
     *
     * @param groupKey the value of groupKey
     * @param fileID   the value of file
     *
     * @return The DrawableGroup the file was removed from.
     *
     */
    public synchronized DrawableGroup removeFromGroup(GroupKey<?> groupKey, final Long fileID) {
        //get grouping this file would be in
        final DrawableGroup group = getGroupForKey(groupKey);
        if (group != null) {
            synchronized (group) {
                group.removeFile(fileID);

                // If we're grouping by category, we don't want to remove empty groups.
                if (groupKey.getAttribute() != DrawableAttribute.CATEGORY
                    && group.getFileIDs().isEmpty()) {
                    if (analyzedGroups.contains(group)) {
                        analyzedGroups.remove(group);
                        sortAnalyzedGroups();
                    }
                    if (unSeenGroups.contains(group)) {
                        unSeenGroups.remove(group);
                        sortUnseenGroups();
                    }

                }
                return group;
            }
        } else { //group == null
            // It may be that this was the last unanalyzed file in the group, so test
            // whether the group is now fully analyzed.
            return popuplateIfAnalyzed(groupKey, null);
        }
    }

    synchronized private void sortUnseenGroups() {
        if (isNotEmpty(unSeenGroups)) {
            FXCollections.sort(unSeenGroups, makeGroupComparator(getSortOrder(), getSortBy()));
        }
    }

    synchronized private void sortAnalyzedGroups() {
        if (isNotEmpty(analyzedGroups)) {
            FXCollections.sort(analyzedGroups, makeGroupComparator(getSortOrder(), getSortBy()));
        }
    }

    synchronized public Set<Long> getFileIDsInGroup(GroupKey<?> groupKey) throws TskCoreException {

        switch (groupKey.getAttribute().attrName) {
            //these cases get special treatment
            case CATEGORY:
                return getFileIDsWithCategory((DhsImageCategory) groupKey.getValue());
            case TAGS:
                return getFileIDsWithTag((TagName) groupKey.getValue());
            case MIME_TYPE:
                return getFileIDsWithMimeType((String) groupKey.getValue());
//            case HASHSET: //comment out this case to use db functionality for hashsets
//                return getFileIDsWithHashSetName((String) groupKey.getValue());
            default:
                //straight db query
                return getDrawableDB().getFileIDsInGroup(groupKey);
        }
    }

    // @@@ This was kind of slow in the profiler.  Maybe we should cache it.
    // Unless the list of file IDs is necessary, use countFilesWithCategory() to get the counts.
    synchronized public Set<Long> getFileIDsWithCategory(DhsImageCategory category) throws TskCoreException {
        Set<Long> fileIDsToReturn = Collections.emptySet();

        try {
            final DrawableTagsManager tagsManager = controller.getTagsManager();
            if (category == DhsImageCategory.ZERO) {
                Set<Long> fileIDs = new HashSet<>();
                for (TagName catTagName : tagsManager.getCategoryTagNames()) {
                    if (notEqual(catTagName.getDisplayName(), DhsImageCategory.ZERO.getDisplayName())) {
                        tagsManager.getContentTagsByTagName(catTagName).stream()
                                .filter(ct -> ct.getContent() instanceof AbstractFile)
                                .map(ct -> ct.getContent().getId())
                                .filter(getDrawableDB()::isInDB)
                                .forEach(fileIDs::add);
                    }
                }

                fileIDsToReturn = getDrawableDB().findAllFileIdsWhere("obj_id NOT IN (" + StringUtils.join(fileIDs, ',') + ")"); //NON-NLS
            } else {

                List<ContentTag> contentTags = tagsManager.getContentTagsByTagName(tagsManager.getTagName(category));
                fileIDsToReturn = contentTags.stream()
                        .filter(ct -> ct.getContent() instanceof AbstractFile)
                        .filter(ct -> getDrawableDB().isInDB(ct.getContent().getId()))
                        .map(ct -> ct.getContent().getId())
                        .collect(Collectors.toSet());
            }
        } catch (TskCoreException ex) {
            logger.log(Level.WARNING, "TSK error getting files in Category:" + category.getDisplayName(), ex); //NON-NLS
            throw ex;
        }

        return fileIDsToReturn;
    }

    synchronized public Set<Long> getFileIDsWithTag(TagName tagName) throws TskCoreException {
        return controller.getTagsManager().getContentTagsByTagName(tagName).stream()
                .map(ContentTag::getContent)
                .filter(AbstractFile.class::isInstance)
                .map(Content::getId)
                .filter(getDrawableDB()::isInDB)
                .collect(Collectors.toSet());
    }

    public synchronized GroupSortBy getSortBy() {
        return sortByProp.get();
    }

    synchronized void setSortBy(GroupSortBy sortBy) {
        sortByProp.set(sortBy);
    }

    public ReadOnlyObjectProperty< GroupSortBy> getSortByProperty() {
        return sortByProp.getReadOnlyProperty();
    }

    public synchronized DrawableAttribute<?> getGroupBy() {
        return groupByProp.get();
    }

    synchronized void setGroupBy(DrawableAttribute<?> groupBy) {
        groupByProp.set(groupBy);
    }

    public ReadOnlyObjectProperty<DrawableAttribute<?>> getGroupByProperty() {
        return groupByProp.getReadOnlyProperty();
    }

    public synchronized SortOrder getSortOrder() {
        return sortOrderProp.get();
    }

    synchronized void setSortOrder(SortOrder sortOrder) {
        sortOrderProp.set(sortOrder);
    }

    public ReadOnlyObjectProperty<SortOrder> getSortOrderProperty() {
        return sortOrderProp.getReadOnlyProperty();
    }

    public synchronized DataSource getDataSource() {
        return dataSourceProp.get();
    }

    synchronized void setDataSource(DataSource dataSource) {
        dataSourceProp.set(dataSource);
    }

    public ReadOnlyObjectProperty<DataSource> getDataSourceProperty() {
        return dataSourceProp.getReadOnlyProperty();
    }

    /**
     * Regroup all files in the database. see ReGroupTask for more details.
     *
     * @param <A>        The type of the values of the groupBy attriubte.
     * @param dataSource The DataSource to show. Null for all data sources.
     * @param groupBy    The DrawableAttribute to group by
     * @param sortBy     The GroupSortBy to sort the groups by
     * @param sortOrder  The SortOrder to use when sorting the groups.
     * @param force      true to force a full db query regroup, even if only the
     *                   sorting has changed.
     */
    public synchronized <A extends Comparable<A>> void regroup(DataSource dataSource, DrawableAttribute<A> groupBy, GroupSortBy sortBy, SortOrder sortOrder, Boolean force) {

        if (!Case.isCaseOpen()) {
            return;
        }

        //only re-query the db if the data source or group by attribute changed or it is forced
        if (dataSource != getDataSource()
            || groupBy != getGroupBy()
            || force) {

            setDataSource(dataSource);
            setGroupBy(groupBy);
            setSortBy(sortBy);
            setSortOrder(sortOrder);
            if (groupByTask != null) {
                groupByTask.cancel(true);
            }
            regrouping = true;
            groupByTask = new ReGroupTask<>(dataSource, groupBy, sortBy, sortOrder);
            Platform.runLater(() -> regroupProgress.bind(groupByTask.progressProperty()));
            exec.submit(groupByTask);
        } else {
            // resort the list of groups
            setSortBy(sortBy);
            setSortOrder(sortOrder);
            sortAnalyzedGroups();
            sortUnseenGroups();
        }
    }

    public ReadOnlyDoubleProperty regroupProgress() {
        return regroupProgress.getReadOnlyProperty();
    }

    @Subscribe
    synchronized public void handleTagAdded(ContentTagAddedEvent evt) {
        GroupKey<?> newGroupKey = null;
        final long fileID = evt.getAddedTag().getContent().getId();
        if (getGroupBy() == DrawableAttribute.CATEGORY && CategoryManager.isCategoryTagName(evt.getAddedTag().getName())) {
            newGroupKey = new GroupKey<>(DrawableAttribute.CATEGORY, CategoryManager.categoryFromTagName(evt.getAddedTag().getName()), getDataSource());
            for (GroupKey<?> oldGroupKey : groupMap.keySet()) {
                if (oldGroupKey.equals(newGroupKey) == false) {
                    removeFromGroup(oldGroupKey, fileID);
                }
            }
        } else if (getGroupBy() == DrawableAttribute.TAGS && CategoryManager.isNotCategoryTagName(evt.getAddedTag().getName())) {
            newGroupKey = new GroupKey<>(DrawableAttribute.TAGS, evt.getAddedTag().getName(), getDataSource());
        }
        if (newGroupKey != null) {
            DrawableGroup g = getGroupForKey(newGroupKey);
            addFileToGroup(g, newGroupKey, fileID);
        }
    }

    @SuppressWarnings("AssignmentToMethodParameter")
    synchronized private void addFileToGroup(DrawableGroup group, final GroupKey<?> groupKey, final long fileID) {
        if (group == null) {
            //if there wasn't already a group check if there should be one now
            group = popuplateIfAnalyzed(groupKey, null);
        }
        if (group != null) {
            //if there is aleady a group that was previously deemed fully analyzed, then add this newly analyzed file to it.
            group.addFile(fileID);
        }
    }

    @Subscribe
    synchronized public void handleTagDeleted(ContentTagDeletedEvent evt) {
        GroupKey<?> groupKey = null;
        final ContentTagDeletedEvent.DeletedContentTagInfo deletedTagInfo = evt.getDeletedTagInfo();
        final TagName tagName = deletedTagInfo.getName();
        if (getGroupBy() == DrawableAttribute.CATEGORY && CategoryManager.isCategoryTagName(tagName)) {
            groupKey = new GroupKey<>(DrawableAttribute.CATEGORY, CategoryManager.categoryFromTagName(tagName), getDataSource());
        } else if (getGroupBy() == DrawableAttribute.TAGS && CategoryManager.isNotCategoryTagName(tagName)) {
            groupKey = new GroupKey<>(DrawableAttribute.TAGS, tagName, getDataSource());
        }
        if (groupKey != null) {
            final long fileID = deletedTagInfo.getContentID();
            DrawableGroup g = removeFromGroup(groupKey, fileID);
        }
    }

    @Subscribe
    synchronized public void handleFileRemoved(Collection<Long> removedFileIDs) {

        for (final long fileId : removedFileIDs) {
            //get grouping(s) this file would be in
            Set<GroupKey<?>> groupsForFile = getGroupKeysForFileID(fileId);

            for (GroupKey<?> gk : groupsForFile) {
                removeFromGroup(gk, fileId);
            }
        }
    }

    /**
     * Handle notifications sent from Db when files are inserted/updated
     *
     * @param updatedFileIDs The ID of the inserted/updated files.
     */
    @Subscribe
    synchronized public void handleFileUpdate(Collection<Long> updatedFileIDs) {
        /**
         * TODO: is there a way to optimize this to avoid quering to db so much.
         * the problem is that as a new files are analyzed they might be in new
         * groups( if we are grouping by say make or model) -jm
         */
        for (long fileId : updatedFileIDs) {

            controller.getHashSetManager().invalidateHashSetsForFile(fileId);

            //get grouping(s) this file would be in
            Set<GroupKey<?>> groupsForFile = getGroupKeysForFileID(fileId);
            for (GroupKey<?> gk : groupsForFile) {
                DrawableGroup g = getGroupForKey(gk);
                addFileToGroup(g, gk, fileId);
            }
        }

        //we fire this event for all files so that the category counts get updated during initial db population
        controller.getCategoryManager().fireChange(updatedFileIDs, null);
    }

    synchronized private DrawableGroup popuplateIfAnalyzed(GroupKey<?> groupKey, ReGroupTask<?> task) {
        /*
         * If this method call is part of a ReGroupTask and that task is
         * cancelled, no-op.
         *
         * This allows us to stop if a regroup task has been cancelled (e.g. the
         * user picked a different group by attribute, while the current task
         * was still running)
         */
        if (isNull(task) || task.isCancelled() == false) {

            /*
             * For attributes other than path we can't be sure a group is fully
             * analyzed because we don't know all the files that will be a part
             * of that group. just show them no matter what.
             */
            if (groupKey.getAttribute() != DrawableAttribute.PATH
                || getDrawableDB().isGroupAnalyzed(groupKey)) {
                try {
                    Set<Long> fileIDs = getFileIDsInGroup(groupKey);
                    if (Objects.nonNull(fileIDs)) {
                        DrawableGroup group;
                        final boolean groupSeen = getDrawableDB().isGroupSeen(groupKey);
                        if (groupMap.containsKey(groupKey)) {
                            group = groupMap.get(groupKey);
                            group.setFiles(ObjectUtils.defaultIfNull(fileIDs, Collections.emptySet()));
                            group.setSeen(groupSeen);
                        } else {
                            group = new DrawableGroup(groupKey, fileIDs, groupSeen);
                            controller.getCategoryManager().registerListener(group);
                            groupMap.put(groupKey, group);
                        }

                        if (analyzedGroups.contains(group) == false) {
                            analyzedGroups.add(group);
                            sortAnalyzedGroups();
                        }
                        updateUnSeenGroups(group, groupSeen);

                        return group;

                    }
                } catch (TskCoreException ex) {
                    logger.log(Level.SEVERE, "failed to get files for group: " + groupKey.getAttribute().attrName.toString() + " = " + groupKey.getValue(), ex); //NON-NLS
                }
            }
        }

        return null;
    }

    synchronized public Set<Long> getFileIDsWithMimeType(String mimeType) throws TskCoreException {

        HashSet<Long> hashSet = new HashSet<>();
        String query = (null == mimeType)
                ? "SELECT obj_id FROM tsk_files WHERE mime_type IS NULL" //NON-NLS
                : "SELECT obj_id FROM tsk_files WHERE mime_type = '" + mimeType + "'"; //NON-NLS

        try (SleuthkitCase.CaseDbQuery executeQuery = controller.getSleuthKitCase().executeQuery(query);
                ResultSet resultSet = executeQuery.getResultSet();) {
            while (resultSet.next()) {
                final long fileID = resultSet.getLong("obj_id"); //NON-NLS
                if (getDrawableDB().isInDB(fileID)) {
                    hashSet.add(fileID);
                }
            }
            return hashSet;

        } catch (Exception ex) {
            throw new TskCoreException("Failed to get file ids with mime type " + mimeType, ex);
        }
    }

    /**
     * Task to query database for files in sorted groups and build
     * DrawableGroups for them.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @NbBundle.Messages({"# {0} - groupBy attribute Name",
        "# {1} - sortBy name",
        "# {2} - sort Order",
        "ReGroupTask.displayTitle=regrouping files by {0} sorted by {1} in {2} order",
        "# {0} - groupBy attribute Name",
        "# {1} - atribute value",
        "ReGroupTask.progressUpdate=regrouping files by {0} : {1}"})
    private class ReGroupTask<AttrValType extends Comparable<AttrValType>> extends LoggedTask<Void> {

        private final DataSource dataSource;
        private final DrawableAttribute<AttrValType> groupBy;
        private final GroupSortBy sortBy;
        private final SortOrder sortOrder;

        private final ProgressHandle groupProgress;

        ReGroupTask(DataSource dataSource, DrawableAttribute<AttrValType> groupBy, GroupSortBy sortBy, SortOrder sortOrder) {
            super(Bundle.ReGroupTask_displayTitle(groupBy.attrName.toString(), sortBy.getDisplayName(), sortOrder.toString()), true);
            this.dataSource = dataSource;
            this.groupBy = groupBy;
            this.sortBy = sortBy;
            this.sortOrder = sortOrder;

            groupProgress = ProgressHandle.createHandle(Bundle.ReGroupTask_displayTitle(groupBy.attrName.toString(), sortBy.getDisplayName(), sortOrder.toString()), this);
        }

        @Override
        protected Void call() throws Exception {
            try {
                if (isCancelled()) {
                    return null;
                }
                groupProgress.start();

                synchronized (GroupManager.this) {
                    analyzedGroups.clear();
                    unSeenGroups.clear();

                    // Get the list of group keys
                    Multimap<DataSource, AttrValType> valsByDataSource = findValuesForAttribute();

                    groupProgress.switchToDeterminate(valsByDataSource.entries().size());
                    int p = 0;
                    // For each key value, partially create the group and add it to the list.
                    for (final Map.Entry<DataSource, AttrValType> val : valsByDataSource.entries()) {
                        if (isCancelled()) {
                            return null;
                        }
                        p++;
                        updateMessage(Bundle.ReGroupTask_progressUpdate(groupBy.attrName.toString(), val.getValue()));
                        updateProgress(p, valsByDataSource.size());
                        groupProgress.progress(Bundle.ReGroupTask_progressUpdate(groupBy.attrName.toString(), val), p);
                        popuplateIfAnalyzed(new GroupKey<>(groupBy, val.getValue(), val.getKey()), this);
                    }
                    regrouping = false;

                    Optional<DrawableGroup> viewedGroup
                            = Optional.ofNullable(controller.getViewState())
                                    .flatMap(GroupViewState::getGroup);
                    Optional<GroupKey<?>> viewedKey = viewedGroup.map(DrawableGroup::getGroupKey);
                    DataSource dataSourceOfCurrentGroup
                            = viewedKey.flatMap(GroupKey::getDataSource)
                                    .orElse(null);
                    DrawableAttribute attributeOfCurrentGroup
                            = viewedKey.map(GroupKey::getAttribute)
                                    .orElse(null);

                    /* if no group or if groupbies are different or if data
                     * source != null and does not equal group */
                    if (viewedGroup.isPresent() == false
                        || (getDataSource() != null && notEqual(dataSourceOfCurrentGroup, getDataSource()))
                        || getGroupBy() != attributeOfCurrentGroup) {
                        //the current group should not be visible so ...
                        if (isNotEmpty(unSeenGroups)) {//  show then next unseen group 
                            controller.advance(GroupViewState.tile(unSeenGroups.get(0)));
                        } else if (isNotEmpty(analyzedGroups)) {
                            //show the first analyzed group.
                            controller.advance(GroupViewState.tile(analyzedGroups.get(0)));
                        } else { //there are no groups,  clear the group area.
                            controller.advance(GroupViewState.tile(null));
                        }
                    }   //else, the current group is for the given datasource, so just keep it in view.
                }
            } finally {
                groupProgress.finish();
                updateProgress(1, 1);
            }
            return null;
        }

        @Override
        protected void done() {
            super.done();
            try {
                get();
            } catch (CancellationException cancelEx) { //NOPMD
                //cancellation is normal
            } catch (InterruptedException | ExecutionException ex) {
                logger.log(Level.SEVERE, "Error while regrouping.", ex);
            }
        }

        /**
         * find the distinct values for the given column (DrawableAttribute)
         *
         * These values represent the groups of files.
         *
         * @param groupBy
         *
         * @return
         */
        public Multimap<DataSource, AttrValType> findValuesForAttribute() {
            synchronized (GroupManager.this) {

                Multimap results = HashMultimap.create();
                try {
                    switch (groupBy.attrName) {
                        //these cases get special treatment
                        case CATEGORY:
                            results.putAll(null, Arrays.asList(DhsImageCategory.values()));
                            break;
                        case TAGS:
                            results.putAll(null, controller.getTagsManager().getTagNamesInUse().stream()
                                    .filter(CategoryManager::isNotCategoryTagName)
                                    .collect(Collectors.toList()));
                            break;

                        case ANALYZED:
                            results.putAll(null, Arrays.asList(false, true));
                            break;
                        case HASHSET:

                            results.putAll(null, new TreeSet<>(getDrawableDB().getHashSetNames()));

                            break;
                        case MIME_TYPE:

                            HashSet<String> types = new HashSet<>();

                            // Use the group_concat function to get a list of files for each mime type.  
                            // This has different syntax on Postgres vs SQLite
                            String groupConcatClause;
                            if (DbType.POSTGRESQL == controller.getSleuthKitCase().getDatabaseType()) {
                                groupConcatClause = " array_to_string(array_agg(obj_id), ',') as object_ids";
                            } else {
                                groupConcatClause = " group_concat(obj_id) as object_ids";
                            }
                            String query = "select " + groupConcatClause + " , mime_type from tsk_files group by mime_type ";
                            try (SleuthkitCase.CaseDbQuery executeQuery = controller.getSleuthKitCase().executeQuery(query); //NON-NLS
                                    ResultSet resultSet = executeQuery.getResultSet();) {
                                while (resultSet.next()) {
                                    final String mimeType = resultSet.getString("mime_type"); //NON-NLS
                                    String objIds = resultSet.getString("object_ids"); //NON-NLS

                                    Pattern.compile(",").splitAsStream(objIds)
                                            .map(Long::valueOf)
                                            .filter(getDrawableDB()::isInDB)
                                            .findAny().ifPresent(obj_id -> types.add(mimeType));
                                }
                            } catch (SQLException | TskCoreException ex) {
                                Exceptions.printStackTrace(ex);
                            }
                            results.putAll(null, types);

                            break;
                        default:
                            //otherwise do straight db query 
                            results.putAll(getDrawableDB().findValuesForAttribute(groupBy, sortBy, sortOrder, dataSource));
                    }
                } catch (TskCoreException ex) {
                    logger.log(Level.SEVERE, "TSK error getting list of type {0}", groupBy.getDisplayName()); //NON-NLS
                }
                return results;
            }
        }
    }

    private static Comparator<DrawableGroup> makeGroupComparator(final SortOrder sortOrder, GroupSortBy comparator) {
        switch (sortOrder) {
            case ASCENDING:
                return comparator;
            case DESCENDING:
                return comparator.reversed();
            case UNSORTED:
            default:
                return new GroupSortBy.AllEqualComparator<>();
        }
    }

    /**
     * @return the drawableDB
     */
    private DrawableDB getDrawableDB() {
        return controller.getDatabase();
    }
}
