package org.javaup.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.javaup.entity.ShowSeat;
import org.javaup.mapper.ShowSeatMapper;
import org.javaup.service.IShowSeatService;
import org.springframework.stereotype.Service;

@Service
public class ShowSeatServiceImpl extends ServiceImpl<ShowSeatMapper, ShowSeat>
        implements IShowSeatService {
}
