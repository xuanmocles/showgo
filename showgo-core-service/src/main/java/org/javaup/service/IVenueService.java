package org.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.javaup.entity.Venue;

import java.util.List;

public interface IVenueService extends IService<Venue> {

    Venue queryById(Long id);

    /** 有演出的城市列表，用于首页筛选 */
    List<String> listCities();

    /** 把场馆经纬度写入 Redis GEO */
    void loadVenueGeo();

    /** 附近的场馆，按距离排序 */
    List<Venue> queryNearby(double x, double y, int page);
}
