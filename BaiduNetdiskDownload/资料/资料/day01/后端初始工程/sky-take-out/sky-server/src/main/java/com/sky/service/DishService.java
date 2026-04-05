package com.sky.service;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.vo.DishVO;

import java.util.List;

public interface DishService {
    //新增菜品和对应的口味
    public void saveWithFlavor(DishDTO dishDTO);
    //分页查询
    PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO);
    //批量删除
    void deleteBatch(List<Long> ids);
    //根据id查询菜品和对应的口味数据
    DishVO getByIdWithFlavor(Long id);
    //修改菜品
    void updateWithFlavor(DishDTO dishDTO);
    //根据分类id查询菜品
    List<Dish> list(Long categoryId);
        /**
         * 条件查询菜品和口味
         * @param dish
         * @return
         */
        List<DishVO> listWithFlavor(Dish dish);
        /**
         * 起售停售
         * @param status
         * @param id
         */
    void startOrStop(Integer status, long id);
}


