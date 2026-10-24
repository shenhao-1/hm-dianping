package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

/**
 * Blog service.
 */
public interface IBlogService extends IService<Blog> {

    Result saveBlog(Blog blog);

    Result queryHotBlog(Integer current);

    Result queryBlogById(Long id);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result queryMyBlog(Integer current);

    Result queryBlogByUser(Long userId, Integer current);

    Result queryBlogOfFollow(Long max, Integer offset);
}