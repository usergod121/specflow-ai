// @requirement R001
package com.example.product.mapper;

import com.example.product.entity.Product;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ProductMapper {

    @Insert("INSERT INTO product(name, price, stock) VALUES(#{name}, #{price}, #{stock})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Product product);

    @Update("UPDATE product SET name = #{name}, price = #{price}, stock = #{stock} WHERE id = #{id}")
    int update(Product product);

    @Delete("DELETE FROM product WHERE id = #{id}")
    int deleteById(Long id);

    @Select("SELECT id, name, price, stock FROM product WHERE id = #{id}")
    Product selectById(Long id);

    @Select("SELECT id, name, price, stock FROM product")
    List<Product> selectAll();

    // @requirement R001
    @Select("SELECT id, name, price, stock FROM product WHERE create_time >= #{startDate} AND create_time <= #{endDate}")
    List<Product> selectByDate(@Param("startDate") String startDate, @Param("endDate") String endDate);

    // @requirement R001
    @Select("<script>" +
            "SELECT id, name, price, stock FROM product" +
            "<where>" +
            "<if test='startDate != null'> AND create_time &gt;= #{startDate}</if>" +
            "<if test='endDate != null'> AND create_time &lt;= #{endDate}</if>" +
            "<if test='model != null'> AND model = #{model}</if>" +
            "<if test='device != null'> AND device = #{device}</if>" +
            "</where>" +
            "</script>")
    List<Product> selectByFilter(@Param("startDate") String startDate,
                                 @Param("endDate") String endDate,
                                 @Param("model") String model,
                                 @Param("device") String device);
}