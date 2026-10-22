package org.javaup.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.javaup.dto.AdminLoginDto;
import org.javaup.dto.AdminSessionCreateDto;
import org.javaup.dto.AdminShowQueryDto;
import org.javaup.dto.AdminShowSaveDto;
import org.javaup.dto.AdminStatusDto;
import org.javaup.dto.Result;
import org.javaup.entity.Show;
import org.javaup.entity.ShowSession;
import org.javaup.entity.Venue;
import org.javaup.service.IAdminService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端接口：登录、演出维护、场次与座位生成。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Resource
    private IAdminService adminService;

    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody AdminLoginDto loginDto) {
        return Result.ok(adminService.login(loginDto));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String token) {
        adminService.logout(token);
        return Result.ok();
    }

    @GetMapping("/venues")
    public Result<List<Venue>> venues() {
        return Result.ok(adminService.listVenues());
    }

    @GetMapping("/shows")
    public Result<List<Show>> shows(AdminShowQueryDto queryDto) {
        Page<Show> page = adminService.pageShows(queryDto);
        Result<List<Show>> result = Result.ok(page.getRecords());
        result.setTotal(page.getTotal());
        return result;
    }

    /** id 有值表示编辑，无值表示新增 */
    @PostMapping("/shows")
    public Result<Show> saveShow(@Valid @RequestBody AdminShowSaveDto saveDto) {
        return Result.ok(adminService.saveShow(saveDto));
    }

    @PutMapping("/shows/{id}/status")
    public Result<Void> updateShowStatus(@PathVariable("id") Long id,
                                         @Valid @RequestBody AdminStatusDto statusDto) {
        adminService.updateShowStatus(id, statusDto.getStatus());
        return Result.ok();
    }

    @DeleteMapping("/shows/{id}")
    public Result<Void> deleteShow(@PathVariable("id") Long id) {
        adminService.deleteShow(id);
        return Result.ok();
    }

    @GetMapping("/shows/{showId}/sessions")
    public Result<List<ShowSession>> sessions(@PathVariable("showId") Long showId) {
        return Result.ok(adminService.listSessions(showId));
    }

    @PostMapping("/sessions")
    public Result<ShowSession> createSession(@Valid @RequestBody AdminSessionCreateDto createDto) {
        return Result.ok(adminService.createSession(createDto));
    }

    @PutMapping("/sessions/{id}/status")
    public Result<Void> updateSessionStatus(@PathVariable("id") Long id,
                                            @Valid @RequestBody AdminStatusDto statusDto) {
        adminService.updateSessionStatus(id, statusDto.getStatus());
        return Result.ok();
    }

    @PostMapping("/sessions/{id}/stock/recompute")
    public Result<Integer> recomputeStock(@PathVariable("id") Long id) {
        return Result.ok(adminService.recomputeStock(id));
    }
}
