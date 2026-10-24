package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;
import static com.hmdp.utils.SystemConstants.MAX_PAGE_SIZE;

/**
 * Blog publishing, querying, likes and follow-feed service.
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private static final int BLOG_LIKES_RANK_SIZE = 5;

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("not login");
        }
        if (blog == null || blog.getShopId() == null || blog.getTitle() == null || blog.getContent() == null) {
            return Result.fail("blog information is incomplete");
        }

        // Never trust ownership and counters supplied by the client.
        blog.setId(null);
        blog.setUserId(currentUser.getId());
        blog.setLiked(0);
        blog.setComments(0);

        if (!save(blog)) {
            return Result.fail("failed to publish blog");
        }

        // Push the blog id into every follower's inbox, ordered by publish time.
        List<Follow> followers = followService.query()
                .eq("follow_user_id", currentUser.getId())
                .list();
        long publishedAt = System.currentTimeMillis();
        for (Follow follower : followers) {
            stringRedisTemplate.opsForZSet().add(
                    FEED_KEY + follower.getUserId(),
                    blog.getId().toString(),
                    publishedAt
            );
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("not login");
        }

        long upperBound = max == null || max <= 0 ? Long.MAX_VALUE : max;
        int queryOffset = offset == null || offset < 0 ? 0 : offset;

        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(
                        FEED_KEY + currentUser.getId(),
                        0,
                        upperBound,
                        queryOffset,
                        DEFAULT_PAGE_SIZE
                );

        ScrollResult scrollResult = new ScrollResult();
        if (tuples == null || tuples.isEmpty()) {
            scrollResult.setList(Collections.emptyList());
            scrollResult.setMinTime(0L);
            scrollResult.setOffset(0);
            return Result.ok(scrollResult);
        }

        List<Long> blogIds = new ArrayList<>(tuples.size());
        long minTime = Long.MAX_VALUE;
        int minTimeCount = 0;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }
            blogIds.add(Long.valueOf(tuple.getValue()));
            long publishTime = tuple.getScore().longValue();
            if (publishTime < minTime) {
                minTime = publishTime;
                minTimeCount = 1;
            } else if (publishTime == minTime) {
                minTimeCount++;
            }
        }

        if (blogIds.isEmpty()) {
            scrollResult.setList(Collections.emptyList());
            scrollResult.setMinTime(0L);
            scrollResult.setOffset(0);
            return Result.ok(scrollResult);
        }

        Map<Long, Blog> blogsById = listByIds(blogIds).stream()
                .collect(Collectors.toMap(Blog::getId, blog -> blog));
        List<Blog> blogs = new ArrayList<>(blogIds.size());
        for (Long blogId : blogIds) {
            Blog blog = blogsById.get(blogId);
            if (blog != null) {
                blogs.add(blog);
            }
        }
        fillBlogDetails(blogs);

        // When the page boundary has the same score as the incoming max, retain the old offset.
        int nextOffset = minTime == upperBound ? queryOffset + minTimeCount : minTimeCount;
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(nextOffset);
        return Result.ok(scrollResult);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .orderByDesc("create_time")
                .page(new Page<>(normalizePage(current), MAX_PAGE_SIZE));
        fillBlogDetails(page.getRecords());
        return Result.ok(page.getRecords());
    }

    @Override
    public Result queryBlogById(Long id) {
        if (id == null) {
            return Result.fail("blog id cannot be null");
        }
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("blog not found");
        }
        fillBlogDetails(Collections.singletonList(blog));
        return Result.ok(blog);
    }

    @Override
    @Transactional
    public Result likeBlog(Long id) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("not login");
        }
        if (id == null) {
            return Result.fail("blog id cannot be null");
        }

        String key = BLOG_LIKED_KEY + id;
        String userId = currentUser.getId().toString();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId);

        boolean updated;
        if (score == null) {
            updated = update()
                    .setSql("liked = IFNULL(liked, 0) + 1")
                    .eq("id", id)
                    .update();
            if (updated) {
                stringRedisTemplate.opsForZSet().add(key, userId, System.currentTimeMillis());
            }
        } else {
            updated = update()
                    .setSql("liked = liked - 1")
                    .eq("id", id)
                    .gt("liked", 0)
                    .update();
            if (updated) {
                stringRedisTemplate.opsForZSet().remove(key, userId);
            }
        }

        if (!updated) {
            return Result.fail("blog not found or like status changed");
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        if (id == null) {
            return Result.fail("blog id cannot be null");
        }

        Set<String> topUserIds = stringRedisTemplate.opsForZSet()
                .range(BLOG_LIKED_KEY + id, 0, BLOG_LIKES_RANK_SIZE - 1);
        if (topUserIds == null || topUserIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> userIds = topUserIds.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        Map<Long, User> usersById = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        List<UserDTO> ranking = new ArrayList<>(userIds.size());
        for (Long userId : userIds) {
            User user = usersById.get(userId);
            if (user != null) {
                ranking.add(BeanUtil.copyProperties(user, UserDTO.class));
            }
        }
        return Result.ok(ranking);
    }

    @Override
    public Result queryMyBlog(Integer current) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("not login");
        }
        return queryBlogPageByUser(currentUser.getId(), current);
    }

    @Override
    public Result queryBlogByUser(Long userId, Integer current) {
        if (userId == null) {
            return Result.fail("user id cannot be null");
        }
        return queryBlogPageByUser(userId, current);
    }

    private Result queryBlogPageByUser(Long userId, Integer current) {
        Page<Blog> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(normalizePage(current), MAX_PAGE_SIZE));
        fillBlogDetails(page.getRecords());
        return Result.ok(page.getRecords());
    }

    /**
     * Batch loads blog authors and marks whether the current user liked each blog.
     */
    private void fillBlogDetails(List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return;
        }

        Set<Long> userIds = blogs.stream()
                .map(Blog::getUserId)
                .collect(Collectors.toSet());
        Map<Long, User> usersById = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(
                        User::getId,
                        user -> user,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        UserDTO currentUser = UserHolder.getUser();
        for (Blog blog : blogs) {
            User author = usersById.get(blog.getUserId());
            if (author != null) {
                blog.setName(author.getNickName());
                blog.setIcon(author.getIcon());
            }

            boolean liked = currentUser != null
                    && stringRedisTemplate.opsForZSet().score(
                    BLOG_LIKED_KEY + blog.getId(),
                    currentUser.getId().toString()
            ) != null;
            blog.setIsLike(liked);
        }
    }

    private long normalizePage(Integer current) {
        return current == null || current < 1 ? 1L : current.longValue();
    }
}