// @requirement R001
package com.example.product.controller;

import com.example.product.entity.Product;
import com.example.product.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/product")
public class ProductController {

    @Autowired
    private ProductService productService;

    @PostMapping
    public int insert(@RequestBody Product product) {
        return productService.insert(product);
    }

    @PutMapping
    public int update(@RequestBody Product product) {
        return productService.update(product);
    }

    @DeleteMapping("/{id}")
    public int deleteById(@PathVariable Long id) {
        return productService.deleteById(id);
    }

    @GetMapping("/{id}")
    public Product selectById(@PathVariable Long id) {
        return productService.selectById(id);
    }

    @GetMapping
    public List<Product> selectAll() {
        return productService.selectAll();
    }

    // @requirement R001
    @GetMapping("/date")
    public List<Product> selectByDate(@RequestParam String startDate, @RequestParam String endDate) {
        return productService.selectByDate(startDate, endDate);
    }
}