package com.wavjaby.db;

import com.wavjaby.jdbc.annotation.*;

@Table(name = "FRIEND", repositoryClass = FriendRepository.class,
        uniqueConstraints = @UniqueConstraint(fieldNames = {"userId", "friendId"}))
public record Friend(
        @JoinColumn(referencedClass = User.class, referencedClassFieldName = "userId")
        long userId,

        @JoinColumn(referencedClass = User.class, referencedClassFieldName = "userId")
        long friendId,

        Boolean accept
) {
}
