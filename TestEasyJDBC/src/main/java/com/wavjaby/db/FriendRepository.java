package com.wavjaby.db;

import com.wavjaby.persistence.*;

import java.util.List;

@SuppressWarnings({"UnusedReturnValue", "BooleanMethodIsAlwaysInverted"})
public interface FriendRepository {

    Friend save(Friend friend);

    @Modifying
    @QuerySQL("ACCEPT IS NULL")
    Friend setAcceptState(@Where long userId, @Where long friendId, Boolean accept);

    @Select(field = "accept")
    Boolean getAcceptState(long userId, long friendId);

    /**
     * Get sent friend request state.
     */
    @QuerySQL("(ACCEPT IS NULL OR ACCEPT=FALSE)")
    List<Friend> getSentRequests(long userId);

    /**
     * Get friend request from other users, acceptance status is not determined.
     */
    @QuerySQL("ACCEPT IS NULL")
    @Select(field = "userId")
    List<Long> getRequests(long friendId);

    @QuerySQL("ACCEPT IS NULL")
    boolean isRequestPending(long userId, long friendId);

    @QuerySQL("(ACCEPT IS NULL OR ACCEPT=FALSE)")
    boolean isRequestExist(long userId, long friendId);

    @QuerySQL("ACCEPT=TRUE")
    @Select(columnSql = """
            case when USER_ID = :userId
                then FRIEND_ID
                else USER_ID
            end as FRIEND_ID""")
    List<Long> getFriendIds(@Where(value = {"userId", "friendId"}) long userId);

    @QuerySQL("ACCEPT=TRUE")
    boolean isFriend(@Where(value = {"userId", "friendId"}) long userIdA,
                     @Where(value = {"userId", "friendId"}) long userIdB);

    @QuerySQL("(ACCEPT IS NULL OR ACCEPT=FALSE)")
    @Delete
    boolean deleteRequest(long userId, long friendId);

    /**
     * Delete friend.
     */
    @QuerySQL("ACCEPT=TRUE")
    @Delete
    boolean delete(@Where(value = {"userId", "friendId"}) long userIdA,
                   @Where(value = {"userId", "friendId"}) long userIdB);
}
