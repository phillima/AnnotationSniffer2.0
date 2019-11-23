package br.inpe.cap.asniffer;

import java.io.FileInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.log4j.Logger;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;

import br.inpe.cap.asniffer.interfaces.IAnnotationMetricCollector;
import br.inpe.cap.asniffer.interfaces.IClassMetricCollector;
import br.inpe.cap.asniffer.interfaces.ICodeElementMetricCollector;
import br.inpe.cap.asniffer.metric.LOCCalculator;
import br.inpe.cap.asniffer.model.AMReport;
import br.inpe.cap.asniffer.model.AnnotationMetricModel;
import br.inpe.cap.asniffer.model.CodeElementModel;
import br.inpe.cap.asniffer.model.MetricResult;
import br.inpe.cap.asniffer.model.PackageModel;
import br.inpe.cap.asniffer.utils.AnnotationUtils;

public class MetricsExecutor extends FileASTRequestor{

	private AMReport report;
	private Map<String, PackageModel> packagesModel;
	MetricResult result = null;
	private Callable<List<IClassMetricCollector>> classMetrics;
	private List<IAnnotationMetricCollector> annotationMetrics;
	private List<ICodeElementMetricCollector> codeElementMetrics;
	
	private static final Logger logger = 
		      Logger.getLogger(MetricsExecutor.class);
	
	public MetricsExecutor(Callable<List<IClassMetricCollector>> classMetrics, List<IAnnotationMetricCollector> annotationMetrics, 
						   List<ICodeElementMetricCollector> codeElementMetrics, String projectName) {
		this.classMetrics = classMetrics;
		this.annotationMetrics = annotationMetrics;
		this.codeElementMetrics = codeElementMetrics;
		this.report = new AMReport(projectName);
		this.packagesModel = new HashMap<String, PackageModel>();
	}

	@Override
	public void acceptAST(String sourceFilePath, 
			CompilationUnit cu) {
		
		try {
			ClassInfo info = new ClassInfo(cu);
			cu.accept(info);
			if(info.getClassName()==null) return;
		
			String packageName = info.getPackageName();
			
			PackageModel packageModel = getPackageModel(packageName);
			
			int loc = new LOCCalculator().calculate(new FileInputStream(sourceFilePath));
			int nec = info.getCodeElementsInfo().size();
			
			result = new MetricResult(sourceFilePath, info.getClassName(), info.getType(),loc, nec);
			logger.info("Initializing extraction of class metrics.");
			//Obtain class metrics
			for(IClassMetricCollector visitor : classMetrics.call()) {
				visitor.execute(cu, result, report);
				visitor.setResult(result);
			}
			logger.info("Finished extracting class metrics.");
			info.getCodeElementsInfo().entrySet().parallelStream().forEach(entry ->{
				BodyDeclaration codeElementBody = entry.getKey();
				CodeElementModel codeElementModel = entry.getValue();
				logger.info("Initializing extraction of code element metrics for element: " + codeElementModel.getElementName());
				//Obtain code element metrics
				for(ICodeElementMetricCollector visitor : codeElementMetrics) {
					visitor.execute(cu, codeElementModel, codeElementBody);
				}
				logger.info("Finished extraction of code element metrics for element: " + codeElementModel.getElementName());
				//Annotation Metrics
				List<Annotation> annotations = AnnotationUtils.checkForAnnotations(codeElementBody);
				
				logger.info("Initializing extraction of annotation metrics for code element: " + codeElementModel.getElementName());
				annotations.parallelStream().forEach(annotation -> {
					AnnotationMetricModel annotationMetricModel = new AnnotationMetricModel(annotation.getTypeName().toString(), 
							   cu.getLineNumber(annotation.getStartPosition()));
					for (IAnnotationMetricCollector annotationCollector : annotationMetrics) {
						annotationCollector.execute(cu, annotationMetricModel, annotation);
					}
					codeElementModel.addAnnotationMetric(annotationMetricModel);
				});
				logger.info("Finished extraction of annotation metrics for code element: " + codeElementModel.getElementName());
				result.addElementReport(codeElementModel);
			});
			
			packageModel.add(result);
			report.addPackageModel(packageModel);
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	public AMReport getReport() {
		return report;
	}
	
	private PackageModel getPackageModel(String packageName) {
		if(packagesModel.containsKey(packageName))
			return packagesModel.get(packageName);
		PackageModel packageModel = new PackageModel(packageName);
		packagesModel.put(packageName, packageModel);
		return packageModel;
	}
	
}
