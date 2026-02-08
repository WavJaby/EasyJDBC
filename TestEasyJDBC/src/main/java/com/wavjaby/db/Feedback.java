package com.wavjaby.db;

import com.wavjaby.jdbc.Table;
import com.wavjaby.persistence.*;

import java.util.List;

@Table(repositoryClass = Feedback.Repository.class)
public record Feedback(
        @Id
        @JoinColumn(referencedClass = User.class, referencedClassFieldName = "userId")
        long userId,

        @NotNull
        long timestamp,

        @Column(length = 256, nullable = false)
        String message
) {
    public interface Repository {
        @BatchInsert
        int save(List<Feedback> feedback);
        
        @Delete
        void deleteById(long userId);
    }
}
