package amazon.microservices.core.product;

import amazon.api.core.product.Product;
import amazon.microservices.core.product.services.ProductValidation;
import amazon.util.exceptions.InvalidInputException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProductValidationTests {
    @Test
    void acceptsLegacyProduct() {
        assertDoesNotThrow(() -> ProductValidation.validate(new Product(1, "Product", 0, null)));
    }

    @Test
    void validatesCatalogMetadata() {
        Product p = new Product(1, "Product", 2, null);
        p.setPrice(new BigDecimal("9.99"));
        assertThrows(InvalidInputException.class, () -> ProductValidation.validate(p));
        p.setCurrency("USD");
        assertDoesNotThrow(() -> ProductValidation.validate(p));
        p.setPrice(new BigDecimal("9.999"));
        assertThrows(InvalidInputException.class, () -> ProductValidation.validate(p));
        p.setPrice(new BigDecimal("9.99"));
        p.setStockQuantity(-1);
        assertThrows(InvalidInputException.class, () -> ProductValidation.validate(p));
        p.setStockQuantity(0);
        p.setImageUrls(List.of("http://unsafe.example.com/image.png"));
        assertThrows(InvalidInputException.class, () -> ProductValidation.validate(p));
    }
}
