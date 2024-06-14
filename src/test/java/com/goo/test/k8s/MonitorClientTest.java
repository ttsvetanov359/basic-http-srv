package com.goo.test.k8s;

import static org.junit.Assert.assertNotNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.api.LabelDescriptor;
import com.google.api.MetricDescriptor;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.monitoring.v3.CreateMetricDescriptorRequest;
import com.google.monitoring.v3.MetricDescriptorName;
import com.google.monitoring.v3.ProjectName;

public class MonitorClientTest {

    private MetricServiceClient client = null;
    private ProjectName projectName = null;
    private MetricDescriptor descriptor = null;
    final String type = "custom.googleapis.com/test/dummy";

    @Before
    public void setUp() throws Exception {
        this.projectName = ProjectName.of("tsvet-prj");
        this.client = MetricServiceClient.create();
        this.descriptor = MetricDescriptor.newBuilder()
                .setType(type)
                .addLabels(
                        LabelDescriptor.newBuilder()
                                .setKey("dummy_lavel")
                                .setValueType(LabelDescriptor.ValueType.STRING))
                .setDescription("This is a dummy example of a custom metric.")
                .setMetricKind(MetricDescriptor.MetricKind.GAUGE)
                .setValueType(MetricDescriptor.ValueType.DOUBLE)
                .build();
        System.out.printf(
                "Project: getProject(%s) toString(%s); MetricServiceClient settings: getEndpoint(%s) getUniverseDomain(%s) getQuotaProjectId(%s)",
                projectName.getProject(),
                projectName.toString(),
                client.getSettings().getEndpoint(),
                client.getSettings().getUniverseDomain(),
                client.getSettings().getQuotaProjectId());
    }

    @After
    public void cleanUp() {
        if (client == null)
            return;
        MetricDescriptorName metricName = MetricDescriptorName.of(this.projectName.getProject(), type);
        client.deleteMetricDescriptor(metricName);
        client.close();
    }

    @Test
    public void testNewMetricCreate() {
        CreateMetricDescriptorRequest request = CreateMetricDescriptorRequest.newBuilder()
                .setName(projectName.toString())
                .setMetricDescriptor(descriptor)
                .build();
        descriptor = client.createMetricDescriptor(request);
        assertNotNull(descriptor);
        System.out.println("Created descriptor " + descriptor.getName());
    }
}
