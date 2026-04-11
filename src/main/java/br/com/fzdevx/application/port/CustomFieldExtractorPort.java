package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.CustomFieldResult;
import br.com.fzdevx.domain.model.LogLine;
import br.com.fzdevx.domain.model.LogPreset;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public interface CustomFieldExtractorPort {

    List<CustomFieldResult> extract(List<LogLine> lines, List<LogPreset.CustomField> customFields);

    List<CustomFieldResult> extract(List<LogLine> lines, List<LogPreset.CustomField> customFields,
                                    AtomicBoolean cancelled);
}
