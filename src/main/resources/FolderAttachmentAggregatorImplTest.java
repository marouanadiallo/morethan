package lu.pgd.dri.saga.service.impl;

// TODO adapter les imports aux packages réels (entités, repos, façades)
import lu.pgd.dri.saga.dto.folder.FolderAttachmentAggregate;
import lu.pgd.dri.saga.jpa.entity.ComponentAttachment;
import lu.pgd.dri.saga.jpa.entity.ComponentEntity;
import lu.pgd.dri.saga.jpa.entity.Task;
import lu.pgd.dri.saga.jpa.entity.TaskAttachment;
import lu.pgd.dri.saga.repository.ComponentAttachmentRepository;
import lu.pgd.dri.saga.repository.SecuredComponentPersistenceFacade;
import lu.pgd.dri.saga.repository.SecuredTaskPersistenceFacade;
import lu.pgd.dri.saga.repository.TaskAttachmentRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FolderAttachmentAggregatorImplTest {

    private static final Long FOLDER_ID = 99L;

    @Mock
    private SecuredComponentPersistenceFacade componentPersistenceFacade;
    @Mock
    private SecuredTaskPersistenceFacade taskPersistenceFacade;
    @Mock
    private ComponentAttachmentRepository componentAttachmentRepository;
    @Mock
    private TaskAttachmentRepository taskAttachmentRepository;

    @InjectMocks
    private FolderAttachmentAggregatorImpl aggregator;

    @Captor
    private ArgumentCaptor<List<Long>> taskIdsCaptor;

    @Test
    void should_return_empty_aggregate_when_no_visible_component() {
        when(componentPersistenceFacade.findComponentsByFolderId(FOLDER_ID)).thenReturn(List.of());

        FolderAttachmentAggregate result = aggregator.attachments(FOLDER_ID);

        assertThat(result.attachments()).isEmpty();
        verifyNoInteractions(taskPersistenceFacade, componentAttachmentRepository, taskAttachmentRepository);
    }

    @Test
    void should_not_query_task_attachments_when_components_have_no_task() {
        ComponentEntity c1 = component(1L, "C-001");
        when(componentPersistenceFacade.findComponentsByFolderId(FOLDER_ID)).thenReturn(List.of(c1));
        when(taskPersistenceFacade.findTasksAssociatedWithComponents(List.of(1L))).thenReturn(List.of());
        when(componentAttachmentRepository.findByComponentIdIn(List.of(1L)))
                .thenReturn(List.of(componentAttachment(100L, "uuid-100", c1)));

        FolderAttachmentAggregate result = aggregator.attachments(FOLDER_ID);

        assertThat(result.attachments()).singleElement().satisfies(ca -> {
            assertThat(ca.id()).isEqualTo(1L);
            assertThat(ca.attachments()).extracting(FolderAttachmentAggregate.Attachment::uuid)
                    .containsExactly("uuid-100");
            assertThat(ca.tasks()).isEmpty();
        });
        verify(taskAttachmentRepository, never()).findByTaskIdIn(any());
    }

    @Test
    void should_aggregate_component_and_task_attachments() {
        // given
        ComponentEntity c1 = component(1L, "C-001");
        ComponentEntity c2 = component(2L, "C-002");
        Task t10 = task(10L, c1);
        Task t11 = task(11L, c1);
        Task t20 = task(20L, c2);

        when(componentPersistenceFacade.findComponentsByFolderId(FOLDER_ID)).thenReturn(List.of(c1, c2));
        when(taskPersistenceFacade.findTasksAssociatedWithComponents(List.of(1L, 2L)))
                .thenReturn(List.of(t10, t11, t20));
        when(componentAttachmentRepository.findByComponentIdIn(List.of(1L, 2L)))
                .thenReturn(List.of(componentAttachment(100L, "uuid-100", c1)));
        // anyList() : l'ordre des taskIds dépend du HashMap de groupingBy
        when(taskAttachmentRepository.findByTaskIdIn(anyList()))
                .thenReturn(List.of(
                        taskAttachment(200L, "uuid-200", t10),
                        taskAttachment(201L, "uuid-201", t10),
                        taskAttachment(202L, "uuid-202", t20)));

        // when
        FolderAttachmentAggregate result = aggregator.attachments(FOLDER_ID);

        // then : une seule requête avec tous les ids de tâches
        verify(taskAttachmentRepository).findByTaskIdIn(taskIdsCaptor.capture());
        assertThat(taskIdsCaptor.getValue()).containsExactlyInAnyOrder(10L, 11L, 20L);

        assertThat(result.attachments()).hasSize(2);

        var comp1 = result.attachments().get(0);
        assertThat(comp1.id()).isEqualTo(1L);
        assertThat(comp1.number()).isEqualTo("C-001");
        assertThat(comp1.attachments()).extracting(FolderAttachmentAggregate.Attachment::uuid)
                .containsExactly("uuid-100");
        assertThat(comp1.tasks()).extracting(FolderAttachmentAggregate.TaskAttachment::id)
                .containsExactly(10L, 11L);
        assertThat(comp1.tasks().get(0).attachments()).extracting(FolderAttachmentAggregate.Attachment::uuid)
                .containsExactlyInAnyOrder("uuid-200", "uuid-201");
        assertThat(comp1.tasks().get(1).attachments()).isEmpty();

        var comp2 = result.attachments().get(1);
        assertThat(comp2.id()).isEqualTo(2L);
        assertThat(comp2.attachments()).isEmpty();
        assertThat(comp2.tasks()).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(20L);
            assertThat(t.attachments()).extracting(FolderAttachmentAggregate.Attachment::uuid)
                    .containsExactly("uuid-202");
        });
    }

    // ---------- helpers (adapter aux constructeurs / setters réels) ----------

    private static ComponentEntity component(Long id, String number) {
        ComponentEntity c = new ComponentEntity();
        c.setId(id);
        c.setNumber(number);
        return c;
    }

    private static Task task(Long id, ComponentEntity component) {
        Task t = new Task();
        t.setId(id);
        t.setComponent(component);
        return t;
    }

    private static ComponentAttachment componentAttachment(Long id, String uuid, ComponentEntity component) {
        ComponentAttachment ca = new ComponentAttachment();
        ca.setId(id);
        ca.setUuid(uuid);
        ca.setComponent(component);
        return ca;
    }

    private static TaskAttachment taskAttachment(Long id, String uuid, Task task) {
        TaskAttachment ta = new TaskAttachment();
        ta.setId(id);
        ta.setUuid(uuid);
        ta.setTask(task);
        return ta;
    }
}
