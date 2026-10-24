package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOWS_INIT_KEY;
import static com.hmdp.utils.RedisConstants.FOLLOWS_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_FOLLOW_KEY;

/**
 * Follow, unfollow and common-follow implementation.
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("not login");
        }
        if (followUserId == null || isFollow == null) {
            return Result.fail("follow parameters are invalid");
        }

        Long userId = currentUser.getId();
        if (userId.equals(followUserId)) {
            return Result.fail("cannot follow yourself");
        }
        if (isFollow && userService.getById(followUserId) == null) {
            return Result.fail("user not found");
        }

        // Initialize old database relationships before applying an incremental Redis update.
        ensureFollowSetInitialized(userId);

        RLock lock = redissonClient.getLock(LOCK_FOLLOW_KEY + userId + ":" + followUserId);
        if (!lock.tryLock()) {
            return Result.fail("follow operation is busy");
        }

        try {
            String redisKey = FOLLOWS_KEY + userId;
            if (isFollow) {
                int count = query()
                        .eq("user_id", userId)
                        .eq("follow_user_id", followUserId)
                        .count();
                if (count == 0) {
                    Follow follow = new Follow();
                    follow.setUserId(userId);
                    follow.setFollowUserId(followUserId);
                    if (!save(follow)) {
                        return Result.fail("failed to follow user");
                    }
                }
                // SADD is idempotent, so retries do not create duplicate Redis relationships.
                stringRedisTemplate.opsForSet().add(redisKey, followUserId.toString());
            } else {
                remove(new QueryWrapper<Follow>()
                        .eq("user_id", userId)
                        .eq("follow_user_id", followUserId));
                // Unfollow is also idempotent, even when the database row no longer exists.
                stringRedisTemplate.opsForSet().remove(redisKey, followUserId.toString());
            }
            return Result.ok();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public Result isFollow(Long followUserId) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("not login");
        }
        if (followUserId == null) {
            return Result.fail("user id cannot be null");
        }

        int count = query()
                .eq("user_id", currentUser.getId())
                .eq("follow_user_id", followUserId)
                .count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long otherUserId) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("not login");
        }
        if (otherUserId == null) {
            return Result.fail("user id cannot be null");
        }
        if (userService.getById(otherUserId) == null) {
            return Result.fail("user not found");
        }

        Long userId = currentUser.getId();
        ensureFollowSetInitialized(userId);
        ensureFollowSetInitialized(otherUserId);

        Set<String> commonIds = stringRedisTemplate.opsForSet()
                .intersect(FOLLOWS_KEY + userId, FOLLOWS_KEY + otherUserId);
        if (commonIds == null || commonIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> userIds = commonIds.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        Map<Long, User> usersById = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        List<UserDTO> commonUsers = new ArrayList<>(userIds.size());
        for (Long id : userIds) {
            User user = usersById.get(id);
            if (user != null) {
                commonUsers.add(BeanUtil.copyProperties(user, UserDTO.class));
            }
        }
        return Result.ok(commonUsers);
    }

    /**
     * Loads pre-existing MySQL relationships into Redis once per user.
     * The marker also represents an initialized empty follow set.
     */
    private void ensureFollowSetInitialized(Long userId) {
        String initKey = FOLLOWS_INIT_KEY + userId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(initKey))) {
            return;
        }

        List<String> followedUserIds = query()
                .eq("user_id", userId)
                .list()
                .stream()
                .map(Follow::getFollowUserId)
                .map(String::valueOf)
                .collect(Collectors.toList());

        if (!followedUserIds.isEmpty()) {
            stringRedisTemplate.opsForSet().add(
                    FOLLOWS_KEY + userId,
                    followedUserIds.toArray(new String[0])
            );
        }
        stringRedisTemplate.opsForValue().set(initKey, "1");
    }
}