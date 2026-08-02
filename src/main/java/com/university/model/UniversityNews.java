package com.university.model;

import java.time.LocalDateTime;

/**
 * One row of {@code dbo.university_news}.
 *
 * <p>This is public news for everybody. A message addressed to one person is
 * a {@link Notification} instead.</p>
 */
public class UniversityNews {

    private int newsId;
    private String title;
    private String content;
    private String imagePath;
    private String category;
    private LocalDateTime publicationDate;
    private LocalDateTime expiryDate;
    private boolean published = true;
    private int createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public UniversityNews() {
    }

    public UniversityNews(int newsId, String title, String content, String imagePath, String category,
                          LocalDateTime publicationDate, LocalDateTime expiryDate, boolean published,
                          int createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.newsId = newsId;
        this.title = title;
        this.content = content;
        this.imagePath = imagePath;
        this.category = category;
        this.publicationDate = publicationDate;
        this.expiryDate = expiryDate;
        this.published = published;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public int getNewsId() {
        return newsId;
    }

    public void setNewsId(int newsId) {
        this.newsId = newsId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public LocalDateTime getPublicationDate() {
        return publicationDate;
    }

    public void setPublicationDate(LocalDateTime publicationDate) {
        this.publicationDate = publicationDate;
    }

    /** Null when the item never expires. */
    public LocalDateTime getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDateTime expiryDate) {
        this.expiryDate = expiryDate;
    }

    public boolean isPublished() {
        return published;
    }

    public void setPublished(boolean published) {
        this.published = published;
    }

    /** The {@code user_id} of the administrator who wrote it. */
    public int getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(int createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** True when the item should be shown at {@code moment}. */
    public boolean isVisible(LocalDateTime moment) {
        if (!published || moment == null || publicationDate == null) {
            return false;
        }
        if (moment.isBefore(publicationDate)) {
            return false;
        }
        return expiryDate == null || moment.isBefore(expiryDate);
    }

    @Override
    public String toString() {
        return title;
    }
}
