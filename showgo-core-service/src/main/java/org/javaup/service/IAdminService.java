package org.javaup.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.javaup.dto.AdminLoginDto;
import org.javaup.dto.AdminSessionCreateDto;
import org.javaup.dto.AdminShowQueryDto;
import org.javaup.dto.AdminShowSaveDto;
import org.javaup.entity.Show;
import org.javaup.entity.ShowSession;
import org.javaup.entity.Venue;

import java.util.List;

/**
 * 管理端：演出与场次维护。
 */
public interface IAdminService {

    /** 登录并返回管理端 token（存 Redis，带 TTL） */
    String login(AdminLoginDto loginDto);

    void logout(String token);

    Page<Show> pageShows(AdminShowQueryDto queryDto);

    /** 新增或编辑演出（id 为空表示新增） */
    Show saveShow(AdminShowSaveDto saveDto);

    void updateShowStatus(Long showId, Integer status);

    void deleteShow(Long showId);

    List<Venue> listVenues();

    List<ShowSession> listSessions(Long showId);

    /** 创建场次：同事务写场次 + 分区 + 批量座位，提交后预热座位图 */
    ShowSession createSession(AdminSessionCreateDto createDto);

    void updateSessionStatus(Long sessionId, Integer status);

    /** 以座位明细为准重算余票，并同步 Redis 余票 */
    int recomputeStock(Long sessionId);
}
