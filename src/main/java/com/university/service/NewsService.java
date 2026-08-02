package com.university.service;

import com.university.dao.UniversityNewsDAO;
import com.university.model.UniversityNews;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The public news board.
 *
 * <p>Distinct from notifications: news is written once and read by everybody,
 * while a notification is addressed to one person.</p>
 */
public class NewsService {

    private final UniversityNewsDAO newsDao = new UniversityNewsDAO();

    /**
     * Publishes an item.
     *
     * @param publishAt when it should appear, or null for immediately
     * @param expiresAt when it should disappear, or null for never
     * @return the new item's key
     */
    public int publish(String title, String content, String category, String imagePath,
                       LocalDateTime publishAt, LocalDateTime expiresAt, int createdByUserId) {
        ValidationException.requireText(title, "Title");
        ValidationException.requireText(content, "Content");
        ValidationException.requireId(createdByUserId, "Author");

        LocalDateTime publication = publishAt == null ? LocalDateTime.now() : publishAt;
        if (expiresAt != null && !expiresAt.isAfter(publication)) {
            throw new ValidationException("The expiry date must be after the publication date.");
        }

        UniversityNews news = new UniversityNews();
        news.setTitle(title.trim());
        news.setContent(content);
        news.setCategory(category);
        news.setImagePath(imagePath);
        news.setPublicationDate(publication);
        news.setExpiryDate(expiresAt);
        news.setPublished(true);
        news.setCreatedBy(createdByUserId);
        return newsDao.insert(news);
    }

    /**
     * Saves changes to an item already on the board.
     *
     * @throws ServiceException when the item no longer exists
     */
    public void edit(UniversityNews news) {
        ValidationException.requireNonNull(news, "News item");
        ValidationException.requireId(news.getNewsId(), "News item");
        ValidationException.requireText(news.getTitle(), "Title");
        ValidationException.requireText(news.getContent(), "Content");

        if (news.getExpiryDate() != null && news.getPublicationDate() != null
                && !news.getExpiryDate().isAfter(news.getPublicationDate())) {
            throw new ValidationException("The expiry date must be after the publication date.");
        }

        news.setUpdatedAt(LocalDateTime.now());
        if (!newsDao.update(news)) {
            throw new ServiceException("That news item no longer exists.");
        }
    }

    /** Takes an item off the board without deleting it. */
    public void unpublish(int newsId) {
        ValidationException.requireId(newsId, "News item");
        if (!newsDao.setPublished(newsId, false, LocalDateTime.now())) {
            throw new ServiceException("That news item no longer exists.");
        }
    }

    /** Puts a withdrawn item back on the board. */
    public void republish(int newsId) {
        ValidationException.requireId(newsId, "News item");
        if (!newsDao.setPublished(newsId, true, LocalDateTime.now())) {
            throw new ServiceException("That news item no longer exists.");
        }
    }

    /** Removes an item for good. */
    public void delete(int newsId) {
        ValidationException.requireId(newsId, "News item");
        if (!newsDao.deleteById(newsId)) {
            throw new ServiceException("That news item no longer exists.");
        }
    }

    /** Everything a reader should see right now. */
    public List<UniversityNews> visible() {
        return newsDao.findVisible();
    }

    /** The newest few, for a dashboard panel. */
    public List<UniversityNews> latest(int howMany) {
        return newsDao.findLatest(Math.max(1, howMany));
    }

    /** Visible items of one category. */
    public List<UniversityNews> byCategory(String category) {
        ValidationException.requireText(category, "Category");
        return newsDao.findByCategory(category);
    }

    /** Everything an author has written, published or not. */
    public List<UniversityNews> writtenBy(int userId) {
        return newsDao.findByCreator(userId);
    }

    /** Every item, for the administration screen. */
    public List<UniversityNews> all() {
        return newsDao.findAll();
    }
}
