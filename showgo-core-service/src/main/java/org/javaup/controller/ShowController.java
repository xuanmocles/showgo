package org.javaup.controller;

import jakarta.annotation.Resource;
import org.javaup.dto.Result;
import org.javaup.dto.ShowQueryDto;
import org.javaup.entity.Show;
import org.javaup.entity.ShowSession;
import org.javaup.service.IShowService;
import org.javaup.service.IShowSessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演出接口：列表、详情、分类、热销场次。
 */
@RestController
@RequestMapping("/show")
public class ShowController {

    @Resource
    private IShowService showService;

    @Resource
    private IShowSessionService showSessionService;

    private static final Map<Integer, String> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put(1, "演唱会");
        CATEGORIES.put(2, "话剧歌剧");
        CATEGORIES.put(3, "音乐节");
        CATEGORIES.put(4, "体育赛事");
        CATEGORIES.put(5, "脱口秀");
        CATEGORIES.put(6, "儿童亲子");
    }

    @GetMapping("/list")
    public Result<List<Show>> list(ShowQueryDto showQueryDto) {
        List<Show> shows = showService.queryShows(showQueryDto);
        Result<List<Show>> result = Result.ok(shows);
        result.setTotal(showService.countShows(showQueryDto));
        return result;
    }

    @GetMapping("/hot/sessions")
    public Result<List<ShowSession>> hotSessions(@RequestParam(value = "limit", defaultValue = "8") Integer limit) {
        return Result.ok(showService.hotSessions(limit));
    }

    @GetMapping("/categories")
    public Result<List<Map<String, Object>>> categories() {
        List<Map<String, Object>> list = new ArrayList<>();
        CATEGORIES.forEach((code, name) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", name);
            list.add(item);
        });
        return Result.ok(list);
    }

    @GetMapping("/{id}")
    public Result<Show> detail(@PathVariable("id") Long id) {
        Show show = showService.queryById(id);
        show.setSessions(showSessionService.listByShow(id));
        return Result.ok(show);
    }
}
