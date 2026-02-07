package com.wavjaby.db;

import com.wavjaby.jdbc.Table;
import com.wavjaby.persistence.JoinColumn;
import com.wavjaby.persistence.UniqueConstraint;

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
