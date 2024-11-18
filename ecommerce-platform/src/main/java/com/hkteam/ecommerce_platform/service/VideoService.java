package com.hkteam.ecommerce_platform.service;

import com.hkteam.ecommerce_platform.enums.TypeImage;
import com.hkteam.ecommerce_platform.rabbitmq.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.hkteam.ecommerce_platform.dto.response.VideoResponse;
import com.hkteam.ecommerce_platform.entity.product.Product;
import com.hkteam.ecommerce_platform.exception.AppException;
import com.hkteam.ecommerce_platform.exception.ErrorCode;
import com.hkteam.ecommerce_platform.repository.ProductRepository;
import com.hkteam.ecommerce_platform.util.AuthenticatedUserUtil;
import com.hkteam.ecommerce_platform.util.VideoUtils;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class VideoService {
    CloudinaryService cloudinaryService;
    ProductRepository productRepository;
    AuthenticatedUserUtil authenticatedUserUtil;
    ExecutorService executorService = Executors.newFixedThreadPool(5);

    @PreAuthorize("hasRole('SELLER')")
    public VideoResponse uploadVideoProduct(String productId, MultipartFile videoFile) {
        VideoUtils.validateVideo(videoFile);

        var user = authenticatedUserUtil.getAuthenticatedUser();

        VideoResponse videoResponse;

        Product product =
                productRepository.findById(productId).orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        var store = product.getStore();

        if (store == null) {
            throw new AppException(ErrorCode.STORE_NOT_FOUND);
        }

        if (!store.getUser().getId().equals(user.getId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        asyncUploadVideo(product.getId(), videoFile);
        videoResponse = VideoResponse.builder()
                .productId(productId)
                .videoUrl("update processing, please wait")
                .build();

        return videoResponse;
    }

    @Async
    void asyncUploadVideo(String productId, MultipartFile videoFile) {
        executorService.submit(() -> {
            try {
                var video = cloudinaryService.uploadVideo(videoFile.getBytes(), TypeImage.MAIN_VIDEO_OF_PRODUCT.toString().toLowerCase());

                if (Objects.isNull(video.get("url"))) {
                    log.error("Error while uploading video: {}", productId);
                }
                Product product =
                        productRepository.findById(productId).orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

                if (product.getVideoUrl() != null) {
                    cloudinaryService.deleteVideo(product.getVideoUrl());
                }

                product.setVideoUrl(video.get("url").toString());

                productRepository.save(product);
            } catch (IOException e) {
                log.error("Error processing video: {}", e.getMessage());
            }
        });

    }

    @PreAuthorize("hasRole('SELLER')")
    public void deleteProductVideo(String productId) {
        var user = authenticatedUserUtil.getAuthenticatedUser();

        Product product =
                productRepository.findById(productId).orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        var store = product.getStore();

        if (store == null) {
            throw new AppException(ErrorCode.STORE_NOT_FOUND);
        }

        if (!product.getStore().getUser().getId().equals(user.getId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        if (product.getVideoUrl() == null) {
            throw new AppException(ErrorCode.FILE_NULL);
        }

        try {
            cloudinaryService.deleteVideo(product.getVideoUrl());

            product.setVideoUrl(null);

            try {
                productRepository.save(product);
            } catch (DataIntegrityViolationException e) {
                log.info("Error while saving at delete product video: {}", e.getMessage());
                throw new AppException(ErrorCode.UNKNOWN_ERROR);
            }
        } catch (Exception e) {
            log.info("Error while deleting at delete product video: {}", e.getMessage());
            throw new AppException(ErrorCode.DELETE_FILE_FAILED);
        }
    }
}
