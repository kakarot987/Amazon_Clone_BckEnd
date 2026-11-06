package amazon.microservices.core.product;

import amazon.api.core.product.Product;
import amazon.microservices.core.product.persistence.ProductEntity;
import amazon.microservices.core.product.services.ProductMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.*;

class MapperTests {

  private ProductMapper mapper = Mappers.getMapper(ProductMapper.class);

  @Test
  void mapperTests() {

    assertNotNull(mapper);

    Product api = new Product(1, "n", 1, "sa");

    ProductEntity entity = mapper.apiToEntity(api);

    assertEquals(api.getProductId(), entity.getProductId());
    assertEquals(api.getProductId(), entity.getProductId());
    assertEquals(api.getName(), entity.getName());
    assertEquals(api.getWeight(), entity.getWeight());

    entity.setSku("SKU-1");
    entity.setCategory("books");
    entity.setPrice(new java.math.BigDecimal("15.50"));
    entity.setCurrency("USD");
    entity.setStockQuantity(7);
    entity.setImageUrls(java.util.List.of("https://example.com/book.jpg"));

    Product api2 = mapper.entityToApi(entity);

    assertEquals(api.getProductId(), api2.getProductId());
    assertEquals(api.getProductId(), api2.getProductId());
    assertEquals(api.getName(),      api2.getName());
    assertEquals(api.getWeight(),    api2.getWeight());
    assertNull(api2.getServiceAddress());
    assertEquals("SKU-1", api2.getSku());
    assertEquals("books", api2.getCategory());
    assertEquals(new java.math.BigDecimal("15.50"), api2.getPrice());
    assertEquals("USD", api2.getCurrency());
    assertEquals(7, api2.getStockQuantity());
    assertEquals(java.util.List.of("https://example.com/book.jpg"), api2.getImageUrls());
  }
}
