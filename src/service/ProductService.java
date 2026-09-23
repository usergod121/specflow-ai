// @requirement R001
package com.example.product.service;

import com.example.product.entity.Product;
import com.example.product.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {

    @Autowired
    private ProductMapper productMapper;

    public int insert(Product product) {
        return productMapper.insert(product);
    }

    public int update(Product product) {
        return productMapper.update(product);
    }

    public int deleteById(Long id) {
        return productMapper.deleteById(id);
    }

    public Product selectById(Long id) {
        return productMapper.selectById(id);
    }

    public List<Product> selectAll() {
        return productMapper.selectAll();
    }

    // @requirement R001
    public List<Product> selectByDate(String startDate, String endDate) {
        return productMapper.selectByDate(startDate, endDate);
    }
}