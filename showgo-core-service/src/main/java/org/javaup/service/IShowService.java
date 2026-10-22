package org.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.javaup.dto.ShowQueryDto;
import org.javaup.entity.Show;
import org.javaup.entity.ShowSession;

import java.util.List;

public interface IShowService extends IService<Show> {

    /** 分页查询演出列表 */
    List<Show> queryShows(ShowQueryDto queryDto);

    long countShows(ShowQueryDto queryDto);

    /** 演出详情，带缓存、空值缓存与布隆过滤器 */
    Show queryById(Long id);

    /** 热销场次（按销量 ZSet） */
    List<ShowSession> hotSessions(int limit);
}
