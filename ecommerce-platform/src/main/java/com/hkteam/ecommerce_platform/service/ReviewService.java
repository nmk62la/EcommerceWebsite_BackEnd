package com.hkteam.ecommerce_platform.service;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hkteam.ecommerce_platform.dto.request.ReviewCreationRequest;
import com.hkteam.ecommerce_platform.dto.response.*;
import com.hkteam.ecommerce_platform.entity.order.OrderItem;
import com.hkteam.ecommerce_platform.entity.order.OrderStatusHistory;
import com.hkteam.ecommerce_platform.entity.product.Product;
import com.hkteam.ecommerce_platform.entity.user.Store;
import com.hkteam.ecommerce_platform.entity.useractions.Review;
import com.hkteam.ecommerce_platform.exception.AppException;
import com.hkteam.ecommerce_platform.exception.ErrorCode;
import com.hkteam.ecommerce_platform.mapper.ReviewMapper;
import com.hkteam.ecommerce_platform.repository.OrderRepository;
import com.hkteam.ecommerce_platform.repository.ProductRepository;
import com.hkteam.ecommerce_platform.repository.ReviewRepository;
import com.hkteam.ecommerce_platform.repository.StoreRepository;
import com.hkteam.ecommerce_platform.util.AuthenticatedUserUtil;
import com.hkteam.ecommerce_platform.util.PageUtils;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class ReviewService {
    ReviewRepository reviewRepository;
    ReviewMapper reviewMapper;
    AuthenticatedUserUtil authenticatedUserUtil;
    OrderRepository orderRepository;
    ProductRepository productRepository;
    StoreRepository storeRepository;

    static String[] SORT_BY = {"createdAt"};
    static String[] ORDER_BY = {"asc", "desc"};

    @Transactional
    public ReviewResponse createReview(ReviewCreationRequest request) {
        var user = authenticatedUserUtil.getAuthenticatedUser();

        var order = orderRepository
                .findById(request.getOrderId())
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getUser().getId().equals(user.getId())) {
            throw new AppException(ErrorCode.ORDER_NOT_BELONG_TO_USER);
        }

        var latestOrderStatus = order.getOrderStatusHistories().stream()
                .max(Comparator.comparing(OrderStatusHistory::getCreatedAt))
                .orElseThrow(() -> new AppException(ErrorCode.STATUS_NOT_FOUND));

        if (!"DELIVERED".equals(latestOrderStatus.getOrderStatus().getName())) {
            throw new AppException(ErrorCode.NOT_PURCHASED);
        }

        boolean hasReviewed = reviewRepository.hasUserAlreadyReviewedOrder(user.getId(), order.getId());
        if (hasReviewed) {
            throw new AppException(ErrorCode.ALREADY_REVIEWED);
        }

        var products = order.getOrderItems().stream().map(OrderItem::getProduct).collect(Collectors.toSet());

        Review review = reviewMapper.toReview(request);
        review.setUser(user);
        review.setProducts(new ArrayList<>(products));

        try {
            reviewRepository.save(review);
            for (Product product : products) {
                updateProductRating(product);
            }
        } catch (DataIntegrityViolationException e) {
            log.error("Error while creating review: " + e.getMessage());
            throw new AppException(ErrorCode.UNKNOWN_ERROR);
        }

        return reviewMapper.toReviewResponse(review);
    }

    private void updateProductRating(Product product) {
        List<Review> reviews = reviewRepository.findAllReviewProductIdCommentAndMediaTotal(product.getId());
        double averageRating =
                reviews.stream().mapToDouble(Review::getRating).average().orElse(0.0);
        float roundedRating = (float) (Math.round(averageRating * 10) / 10.0);
        product.setRating(roundedRating);
        productRepository.save(product);

        updateStoreRating(product.getStore());
    }

    private void updateStoreRating(Store store) {
        Set<Product> products = store.getProducts();
        double averageRating = products.stream()
                .filter(p -> p.getRating() != null)
                .mapToDouble(Product::getRating)
                .average()
                .orElse(0.0);
        float storeRating = (float) (Math.round(averageRating * 10) / 10.0);
        store.setRating(storeRating);
        storeRepository.save(store);
    }

    public PaginationResponse<ReviewOneProductResponse> getReviewOneProduct(
            String productId,
            String starNumber,
            String commentString,
            String mediaString,
            String pageStr,
            String sizeStr,
            String sortBy,
            String orderBy) {
        var product =
                productRepository.findById(productId).orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        if (!Arrays.asList(SORT_BY).contains(sortBy)) sortBy = null;
        if (!Arrays.asList(ORDER_BY).contains(orderBy)) orderBy = null;
        Sort sortable = (sortBy == null || orderBy == null)
                ? Sort.unsorted()
                : Sort.by(Sort.Direction.fromString(orderBy), sortBy);

        Pageable pageable = PageUtils.createPageable(pageStr, sizeStr, sortable);
        var pageData = reviewRepository.findAllReviewProductId(
                productId,
                starNumber,
                commentString.isEmpty() ? "" : "commentString",
                mediaString.isEmpty() ? "" : "mediaString",
                pageable);
        int page = Integer.parseInt(pageStr);
        PageUtils.validatePageBounds(page, pageData);

        List<ReviewListOneProductResponse> reviewResponses = pageData.stream()
                .map(review -> {
                    ReviewListOneProductResponse response = reviewMapper.toReviewListOneProductResponse(review);
                    response.setUser(reviewMapper.toReviewProductUserResponse(review.getUser()));
                    return response;
                })
                .toList();

        List<Object[]> ratingCounts = reviewRepository.countReviewsByRating(productId);
        Map<Float, Long> ratingCountMap = new HashMap<>();
        for (Object[] row : ratingCounts) {
            Float rating = ((Number) row[0]).floatValue();
            Long count = (Long) row[1];
            ratingCountMap.put(rating, count);
        }

        RatingCountResponse ratingCountResponse = RatingCountResponse.builder()
                .fiveStar(ratingCountMap.getOrDefault(5f, 0L))
                .fourStar(ratingCountMap.getOrDefault(4f, 0L))
                .threeStar(ratingCountMap.getOrDefault(3f, 0L))
                .twoStar(ratingCountMap.getOrDefault(2f, 0L))
                .oneStar(ratingCountMap.getOrDefault(1f, 0L))
                .build();

        ReviewOneProductResponse reviewOneProductResponse = ReviewOneProductResponse.builder()
                .productRating(product.getRating() != null ? product.getRating() : 0)
                .reviews(reviewResponses)
                .ratingCounts(ratingCountResponse)
                .build();
        List<ReviewOneProductResponse> reviewOneProductResponseList = new ArrayList<>();
        reviewOneProductResponseList.add(reviewOneProductResponse);

        return PaginationResponse.<ReviewOneProductResponse>builder()
                .data(reviewOneProductResponseList)
                .currentPage(Integer.parseInt(pageStr))
                .pageSize(pageData.getSize())
                .totalPages(pageData.getTotalPages())
                .totalElements(pageData.getTotalElements())
                .hasNext(pageData.hasNext())
                .hasPrevious(pageData.hasPrevious())
                .nextPage(pageData.hasNext() ? page + 1 : null)
                .previousPage(pageData.hasPrevious() ? page - 1 : null)
                .build();
    }

    public ReviewCountResponse getCommentAndMediaTotalReview(String productId) {
        List<Review> allReviews = reviewRepository.findAllReviewProductIdCommentAndMediaTotal(productId);

        long totalComments = allReviews.stream()
                .filter(review ->
                        review.getComment() != null && !review.getComment().isEmpty())
                .count();

        long totalWithMedia = allReviews.stream()
                .filter(review -> (review.getVideoUrl() != null
                                && !review.getVideoUrl().isEmpty())
                        || (review.getImages() != null && !review.getImages().isEmpty()))
                .count();

        return ReviewCountResponse.builder()
                .totalComments(totalComments)
                .totalWithMedia(totalWithMedia)
                .build();
    }
}
