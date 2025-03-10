package com.hkteam.ecommerce_platform.service;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.hkteam.ecommerce_platform.dto.request.ReviewVideoUploadRequest;
import com.hkteam.ecommerce_platform.dto.response.VideoResponse;
import com.hkteam.ecommerce_platform.dto.response.VideoReviewResponse;
import com.hkteam.ecommerce_platform.entity.product.Product;
import com.hkteam.ecommerce_platform.enums.TypeImage;
import com.hkteam.ecommerce_platform.exception.AppException;
import com.hkteam.ecommerce_platform.exception.ErrorCode;
import com.hkteam.ecommerce_platform.repository.ProductElasticsearchRepository;
import com.hkteam.ecommerce_platform.repository.ProductRepository;
import com.hkteam.ecommerce_platform.repository.ReviewRepository;
import com.hkteam.ecommerce_platform.util.AuthenticatedUserUtil;
import com.hkteam.ecommerce_platform.util.VideoUtils;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class VideoService {
    CloudinaryService cloudinaryService;
    ProductRepository productRepository;
    AuthenticatedUserUtil authenticatedUserUtil;
    ExecutorService executorService = Executors.newFixedThreadPool(5);
    ProductElasticsearchRepository productElasticsearchRepository;
    ReviewRepository reviewRepository;

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
    @Transactional
    void asyncUploadVideo(String productId, MultipartFile videoFile) {
        executorService.submit(() -> {
            try {
                var video = cloudinaryService.uploadVideo(
                        videoFile.getBytes(),
                        TypeImage.MAIN_VIDEO_OF_PRODUCT.toString().toLowerCase());

                if (Objects.isNull(video.get("url"))) {
                    log.error("Error while uploading video: {}", productId);
                }
                Product product = productRepository
                        .findById(productId)
                        .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

                if (product.getVideoUrl() != null) {
                    cloudinaryService.deleteVideo(product.getVideoUrl());
                }

                productRepository.updateVideoUrlById(video.get("url").toString(), productId);
                var esPro =
                        productElasticsearchRepository.findById(product.getId()).orElse(null);
                if (esPro != null) {
                    esPro.setVideoUrl(video.get("url").toString());
                }

                productElasticsearchRepository.save(esPro);

            } catch (IOException e) {
                log.error("Error processing video: {}", e.getMessage());
            }
        });
    }

    @PreAuthorize("hasRole('SELLER')")
    @Transactional
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

            var esPro = productElasticsearchRepository.findById(product.getId()).orElse(null);
            if (esPro != null) {
                esPro.setVideoUrl(null);
            }

            product.setVideoUrl(null);
            productRepository.save(product);
            productElasticsearchRepository.save(esPro);

        } catch (Exception e) {
            log.info("Error while deleting at delete product video: {}", e.getMessage());
            throw new AppException(ErrorCode.DELETE_FILE_FAILED);
        }
    }

    @PreAuthorize("hasRole('USER')")
    @Transactional
    public VideoReviewResponse uploadVideoReview(ReviewVideoUploadRequest request, String reviewId) {
        VideoUtils.validateVideo(request.getVideo());

        Long longReviewId = Long.parseLong(reviewId);
        var review =
                reviewRepository.findById(longReviewId).orElseThrow(() -> new AppException(ErrorCode.REVIEW_NOT_FOUND));

        try {
            if (review.getVideoUrl() != null && !review.getVideoUrl().isEmpty()) {
                cloudinaryService.deleteVideo(review.getVideoUrl());
            }

            byte[] videoBytes = request.getVideo().getBytes();
            Map<String, Object> uploadResult = cloudinaryService.uploadVideo(
                    videoBytes, TypeImage.VIDEO_REVIEW.name().toLowerCase());
            if (uploadResult.get("url") == null) {
                throw new AppException(ErrorCode.UPLOAD_FILE_FAILED);
            }
            review.setVideoUrl(uploadResult.get("url").toString());
            reviewRepository.save(review);

            return VideoReviewResponse.builder()
                    .videoUrl(uploadResult.get("url").toString())
                    .secureUrl((String) uploadResult.get("secure_url"))
                    .format((String) uploadResult.get("format"))
                    .assetId((String) uploadResult.get("asset_id"))
                    .createdAt((String) uploadResult.get("created_at"))
                    .size((Integer) uploadResult.get("bytes"))
                    .build();

        } catch (Exception e) {
            log.error("Error while uploading review video: {}", e.getMessage());
            throw new AppException(ErrorCode.UPLOAD_FILE_FAILED);
        }
    }
}
