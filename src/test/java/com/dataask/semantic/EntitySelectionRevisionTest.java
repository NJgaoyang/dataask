package com.dataask.semantic;
import com.dataask.data.StarRocksGateway;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class EntitySelectionRevisionTest {
    @Test void changingSourceOrSelectionInvalidatesCityCache() {
        var gateway=mock(StarRocksGateway.class);
        var resolver=new EntityValueResolver(gateway);
        var metadata=List.of(new StarRocksGateway.TableMeta("ads.revenue",List.of(new StarRocksGateway.ColumnMeta("city","varchar","城市名称"))));
        when(gateway.selectionRevision()).thenReturn("source-A:one", "source-B:two");
        when(gateway.distinctDimensionValues("ads.revenue","city")).thenReturn(List.of("西安市"), List.of("杭州市"));
        assertThat(resolver.resolve("西安收入",Set.of("ads.revenue"),metadata)).hasSize(1);
        assertThat(resolver.resolve("西安收入",Set.of("ads.revenue"),metadata)).isEmpty();
        verify(gateway,times(2)).distinctDimensionValues("ads.revenue","city");
    }
}
