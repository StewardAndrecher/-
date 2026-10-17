package com.campus.market.publication;
import com.campus.market.product.CreateProductRequest;
public record CreatePublicationRequest(String requestId, CreateProductRequest product) {}
