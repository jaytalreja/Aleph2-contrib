/*******************************************************************************
 * Copyright 2015, The IKANOW Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.ikanow.aleph2.analytics.hadoop.services;

import java.io.IOException;
import java.io.File;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.io.Files;
import com.google.inject.Inject;
import com.ikanow.aleph2.analytics.hadoop.assets.Aleph2MultiInputFormatBuilder;
import com.ikanow.aleph2.analytics.hadoop.assets.BatchEnrichmentJob;
import com.ikanow.aleph2.analytics.hadoop.assets.BeFileInputFormat;
import com.ikanow.aleph2.analytics.hadoop.assets.BeFileOutputFormat;
import com.ikanow.aleph2.analytics.hadoop.assets.ObjectNodeWritableComparable;
import com.ikanow.aleph2.analytics.hadoop.data_model.IBeJobService;
import com.ikanow.aleph2.analytics.hadoop.data_model.HadoopTechnologyOverrideBean;
import com.ikanow.aleph2.analytics.hadoop.utils.HadoopTechnologyUtils;
import com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsAccessContext;
import com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.shared.GlobalPropertiesBean;
import com.ikanow.aleph2.data_model.objects.shared.ProcessingTestSpecBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.BucketUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.Optionals;
import com.ikanow.aleph2.data_model.utils.SetOnce;
import com.ikanow.aleph2.data_model.utils.Tuples;
import com.ikanow.aleph2.data_model.utils.UuidUtils;

import fj.Unit;
import fj.data.Validation;

/** Responsible for launching the hadoop job
 * @author jfreydank
 */
public class BeJobLauncher implements IBeJobService{

	//TODO (ALEPH-12): sort out test spec
	
	private static final Logger logger = LogManager.getLogger(BeJobLauncher.class);

	protected Configuration _configuration;
	protected GlobalPropertiesBean _globals = null;

	protected String _yarnConfig = null;

	protected BatchEnrichmentContext _batchEnrichmentContext;

	@SuppressWarnings("rawtypes")
	public static interface HadoopAccessContext extends IAnalyticsAccessContext<InputFormat> {}
	
	/** User/guice c'tor
	 * @param globals
	 * @param beJobLoader
	 * @param batchEnrichmentContext
	 */
	@Inject
	public BeJobLauncher(GlobalPropertiesBean globals, BatchEnrichmentContext batchEnrichmentContext) {
		_globals = globals;	
		this._batchEnrichmentContext = batchEnrichmentContext;
	}
	
	/** 
	 * Override this function with system specific configuration
	 * @return
	 */
	public Configuration getHadoopConfig(){
		if(_configuration == null){
			_configuration = HadoopTechnologyUtils.getHadoopConfig(_globals);
		}
		return _configuration;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.analytics.hadoop.services.IBeJobService#runEnhancementJob(java.lang.String, java.lang.String, java.lang.String)
	 */
	@Override
	public Validation<String, Job> runEnhancementJob(final DataBucketBean bucket, final Optional<ProcessingTestSpecBean> testSpec){
		
		final Configuration config = getHadoopConfig();
		
		final ClassLoader currentClassloader = Thread.currentThread().getContextClassLoader();
		//(not currently used, but has proven useful in the past)
		
		final SetOnce<Job> job = new SetOnce<>();
		try {
			final String contextSignature = _batchEnrichmentContext.getEnrichmentContextSignature(Optional.of(bucket), Optional.empty()); 
		    config.set(BatchEnrichmentJob.BE_CONTEXT_SIGNATURE, contextSignature);
			
		    final Optional<Long> debug_max = 
		    		testSpec.flatMap(testSpecVals -> 
		    							Optional.ofNullable(testSpecVals.requested_num_objects()));
		    
		    //then gets applied to all the inputs:
		    debug_max.ifPresent(val -> config.set(BatchEnrichmentJob.BE_DEBUG_MAX_SIZE, val.toString()));
		    
		    final Aleph2MultiInputFormatBuilder inputBuilder = new Aleph2MultiInputFormatBuilder();

		    // Create a separate InputFormat for every input (makes testing life easier)
		    
			Optional.ofNullable(_batchEnrichmentContext.getJob().inputs())
						.orElse(Collections.emptyList())
					.stream()
					.forEach(Lambdas.wrap_consumer_u(input -> {
						final List<String> paths = _batchEnrichmentContext.getAnalyticsContext().getInputPaths(Optional.of(bucket), _batchEnrichmentContext.getJob(), input);
						
						if (!paths.isEmpty()) {
						
							logger.info(ErrorUtils.get("Adding storage paths for bucket {0}: {1}", bucket.full_name(), paths.stream().collect(Collectors.joining(";"))));
							
						    final Job inputJob = Job.getInstance(config);
						    inputJob.setInputFormatClass(BeFileInputFormat.class);				
							paths.stream().forEach(Lambdas.wrap_consumer_u(path -> FileInputFormat.addInputPath(inputJob, new Path(path))));
							inputBuilder.addInput(UuidUtils.get().getRandomUuid(), inputJob);
						}
						else { // not easily available in HDFS directory format, try getting from the context
							
							Optional<HadoopAccessContext> input_format_info = _batchEnrichmentContext.getAnalyticsContext().getServiceInput(HadoopAccessContext.class, Optional.of(bucket), _batchEnrichmentContext.getJob(), input);
							if (!input_format_info.isPresent()) {
								logger.warn(ErrorUtils.get("Tried but failed to get input format from {0}", BeanTemplateUtils.toJson(input)));
							}
							else {
								logger.info(ErrorUtils.get("Adding data service path for bucket {0}: {1} ({2})", bucket.full_name()),
										input_format_info.get().getAccessService().either(l -> l.getClass().getSimpleName(), r -> r.getSimpleName()),
										input_format_info.get().getAccessConfig().map(cfg -> cfg.size()).orElse(0)
										);
								
							    final Job inputJob = Job.getInstance(config);
							    inputJob.setInputFormatClass(input_format_info.get().getAccessService().either(l -> l.getClass(), r -> r));
							    input_format_info.get().getAccessConfig().ifPresent(map -> {
							    	map.entrySet().forEach(kv -> inputJob.getConfiguration().set(kv.getKey(), kv.getValue().toString()));
							    });							    
							    inputBuilder.addInput(UuidUtils.get().getRandomUuid(), inputJob);
							}
						}
					}));
					;					
					
			// (ALEPH-12): other input format types
			
		    // Now do everything else
		    
			final String jobName = BucketUtils.getUniqueSignature(bucket.full_name(), Optional.ofNullable(_batchEnrichmentContext.getJob().name()));
			
			// Try to minimize class conflicts vs Hadoop's ancient libraries:
			config.set("mapreduce.job.user.classpath.first", "true");
			
			// Get max batch size, apply that everywhere:
			final Optional<Integer> max_requested_batch_size = 
			    Optional.ofNullable(bucket.batch_enrichment_configs()).orElse(Collections.emptyList())
					.stream()
					.filter(cfg -> Optional.ofNullable(cfg.enabled()).orElse(true))
	    			.<Integer>flatMap(cfg -> {
	    				final HadoopTechnologyOverrideBean tech_override = 
	    						BeanTemplateUtils.from(Optional.ofNullable(cfg.technology_override()).orElse(Collections.emptyMap()), 
	    								HadoopTechnologyOverrideBean.class)
	    								.get();
	    				return Optional.ofNullable(tech_override.requested_batch_size()).map(Stream::of).orElseGet(Stream::empty);
	    			})
	    			.max(Integer::max)
	    			;
			max_requested_batch_size.ifPresent(i -> config.set(BatchEnrichmentJob.BATCH_SIZE_PARAM, Integer.toString(i)));
			
		    // do not set anything into config past this line (can set job.getConfiguration() elements though - that is what the builder does)
		    job.set(Job.getInstance(config, jobName));
		    job.get().setJarByClass(BatchEnrichmentJob.class);
		    job.get().setSortComparatorClass(ObjectNodeWritableComparable.Comparator.class); //(avoid deser of json node for intermediate things)
		    
		    // Set the classpath
		    
		    cacheJars(job.get(), bucket, _batchEnrichmentContext.getAnalyticsContext());
		    
		    // (generic mapper - the actual code is run using the classes in the shared libraries)
		    job.get().setMapperClass(BatchEnrichmentJob.BatchEnrichmentMapper.class);
		    
		    // (combiner and reducer)
		    Optional.ofNullable(bucket.batch_enrichment_configs()).orElse(Collections.emptyList())
		    			.stream()
		    			.filter(cfg -> Optional.ofNullable(cfg.enabled()).orElse(true))
		    			.filter(cfg -> !Optionals.ofNullable(cfg.grouping_fields()).isEmpty())
		    			.findAny()
		    			.map(cfg -> {
		    				final HadoopTechnologyOverrideBean tech_override = 
		    						BeanTemplateUtils.from(Optional.ofNullable(cfg.technology_override()).orElse(Collections.emptyMap()), 
		    								HadoopTechnologyOverrideBean.class)
		    								.get();
		    				
		    				job.get().setNumReduceTasks(Optional.ofNullable(tech_override.num_reducers()).orElse(2));
		    				job.get().setReducerClass(BatchEnrichmentJob.BatchEnrichmentReducer.class);
		    				
		    				if (tech_override.use_combiner()) {
		    					job.get().setCombinerClass(BatchEnrichmentJob.BatchEnrichmentCombiner.class);
		    				}		    				
		    				return Unit.unit();
		    			})
		    			.orElseGet(() -> {
		    				job.get().setNumReduceTasks(0);
		    				return Unit.unit();
		    			})
		    			;
		    
		    //job.setReducerClass(BatchEnrichmentJob.BatchEnrichmentReducer.class);

		    // Input format:
		    inputBuilder.build(job.get());

			// Output format (doesn't really do anything, all the actual output code is performed by the mapper via the enrichment context)
		    job.get().setOutputFormatClass(BeFileOutputFormat.class);
			
			launch(job.get());
			return Validation.success(job.get());
			
		} 
		catch (Throwable t) { 
			Throwable tt = (t instanceof RuntimeException)
							? (null != t.getCause()) ? t.getCause() : t
							: t;
			
			if (tt instanceof org.apache.hadoop.mapreduce.lib.input.InvalidInputException) {
				// Probably a benign "no matching paths", so return pithy error
				return Validation.fail(ErrorUtils.get("{0}", tt.getMessage()));				
			}
			else { // General error : Dump the config params to string			
				if (job.isSet()) {
					logger.error(ErrorUtils.get("Error submitting, config= {0}",  
							Optionals.streamOf(job.get().getConfiguration().iterator(), false).map(kv -> kv.getKey() + ":" + kv.getValue()).collect(Collectors.joining("; "))));
				}			
				return Validation.fail(ErrorUtils.getLongForm("{0}", tt));
			}
		}
		finally {
			Thread.currentThread().setContextClassLoader(currentClassloader);
		}
	     		
	}
	
	/** Cache the system and user classpaths
	 * @param job
	 * @param context
	 * @throws IOException 
	 * @throws ExecutionException 
	 * @throws InterruptedException 
	 * @throws IllegalArgumentException 
	 */
	protected static void cacheJars(final Job job, final DataBucketBean bucket, final IAnalyticsContext context) throws IllegalArgumentException, InterruptedException, ExecutionException, IOException {
	    final FileContext fc = context.getServiceContext().getStorageService().getUnderlyingPlatformDriver(FileContext.class, Optional.empty()).get();
	    final String rootPath = context.getServiceContext().getStorageService().getRootPath();
	    
	    // Aleph2 libraries: need to cache them
	    context
	    	.getAnalyticsContextLibraries(Optional.empty())
	    	.stream()
	    	.map(f -> new File(f))
	    	.map(f -> Tuples._2T(f, new Path(rootPath + "/" + f.getName())))
	    	.map(Lambdas.wrap_u(f_p -> {
	    		final FileStatus fs = Lambdas.get(() -> {
	    			//TODO (ALEPH-12): need to clear out the cache intermittently
		    		try {
		    			return fc.getFileStatus(f_p._2());
		    		}
		    		catch (Exception e) { return null; }
	    		});
	    		if (null == fs) { //cache doesn't exist
	    			// Local version
	    			try (FSDataOutputStream outer = fc.create(f_p._2(), EnumSet.of(CreateFlag.CREATE), // ie should fail if the destination file already exists 
	    					org.apache.hadoop.fs.Options.CreateOpts.createParent()))
	    			{
	    				Files.copy(f_p._1(), outer.getWrappedStream());
	    			}
	    			catch (FileAlreadyExistsException e) {//(carry on - the file is versioned so it can't be out of date)
	    			}
	    		}
	    		return f_p._2();
	    	}))
	    	.forEach(Lambdas.wrap_consumer_u(path -> job.addFileToClassPath(path)));
	    	;
	    
	    // User libraries: this is slightly easier since one of the 2 keys
	    // is the HDFS path (the other is the _id)
	    context
			.getAnalyticsLibraries(Optional.of(bucket), bucket.analytic_thread().jobs())
			.get()
			.entrySet()
			.stream()
			.map(kv -> kv.getKey())
			.filter(path -> path.startsWith(rootPath))
			.forEach(Lambdas.wrap_consumer_u(path -> job.addFileToClassPath(new Path(path))));
			;	    		
	}
	
	/** Launches the job
	 * @param job
	 * @throws ClassNotFoundException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void launch(Job job) throws ClassNotFoundException, IOException, InterruptedException{
		job.submit();
	}

}

