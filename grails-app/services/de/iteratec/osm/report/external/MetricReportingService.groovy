/* 
* OpenSpeedMonitor (OSM)
* Copyright 2014 iteratec GmbH
* 
* Licensed under the Apache License, Version 2.0 (the "License"); 
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* 
* 	http://www.apache.org/licenses/LICENSE-2.0
* 
* Unless required by applicable law or agreed to in writing, software 
* distributed under the License is distributed on an "AS IS" BASIS, 
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
* See the License for the specific language governing permissions and 
* limitations under the License.
*/

package de.iteratec.osm.report.external

import grails.transaction.NotTransactional
import grails.transaction.Transactional

import org.joda.time.DateTime

import de.iteratec.osm.report.chart.MeasuredValueUtilService
import de.iteratec.osm.measurement.schedule.JobGroup
import de.iteratec.osm.measurement.schedule.dao.JobGroupDaoService
import de.iteratec.osm.csi.Page
import de.iteratec.osm.ConfigService
import de.iteratec.osm.report.chart.AggregatorType
import de.iteratec.osm.report.chart.MeasurandGroup
import de.iteratec.osm.report.chart.MeasuredValue
import de.iteratec.osm.report.chart.MeasuredValueInterval
import de.iteratec.osm.report.external.provider.GraphiteSocketProvider
import de.iteratec.osm.csi.EventMeasuredValueService
import de.iteratec.osm.csi.PageMeasuredValueService
import de.iteratec.osm.csi.ShopMeasuredValueService
import de.iteratec.osm.result.Contract
import de.iteratec.osm.result.EventResult
import de.iteratec.osm.result.MeasuredEvent
import de.iteratec.osm.result.MeasuredValueTagService
import de.iteratec.osm.result.MvQueryParams
import de.iteratec.osm.result.ResultMeasuredValueService
import de.iteratec.osm.measurement.environment.Browser
import de.iteratec.osm.measurement.environment.Location
import de.iteratec.osm.util.I18nService


/**
 * Reports osm-metrics to external tools.
 */
@Transactional
class MetricReportingService {
	
	private static final List<String> INVALID_GRAPHITE_PATH_CHARACTERS = ['.', ' ']
	private static final String REPLACEMENT_FOR_INVALID_GRAPHITE_PATH_CHARACTERS = '_'

	MeasuredValueTagService measuredValueTagService
	ResultMeasuredValueService resultMeasuredValueService
	GraphiteSocketProvider graphiteSocketProvider
	I18nService i18nService
	EventMeasuredValueService eventMeasuredValueService
	JobGroupDaoService jobGroupDaoService
	MeasuredValueUtilService measuredValueUtilService
	PageMeasuredValueService pageMeasuredValueService
	ShopMeasuredValueService shopMeasuredValueService
	ConfigService configService

	/**
	 * Reports each measurand of incoming result for that a {@link GraphitePath} is configured.  
	 * @param result
	 * 				This EventResult defines measurands to sent and must not be null.
	 * @throws NullPointerException
	 *             if {@code pathElements} is <code>null</code>.
	 * @throws IllegalArgumentException
	 *             if at least one of the {@code pathElements} is
	 *             {@linkplain String#isEmpty() empty} or contains at least one
	 *             dot.
	 */
	@NotTransactional
	public void reportEventResultToGraphite(EventResult result) {

		Contract.requiresArgumentNotNull("result", result)

		log.info("reporting Eventresult");

		JobGroup jobGroup = measuredValueTagService.findJobGroupOfHourlyEventTag(result.tag)
		Collection<GraphiteServer> servers = jobGroup.graphiteServers
		if (servers.size()<1) {
			return null
		}

		MeasuredEvent event = measuredValueTagService.findMeasuredEventOfHourlyEventTag(result.tag);
		Page page = measuredValueTagService.findPageOfHourlyEventTag(result.tag);
		Browser browser = measuredValueTagService.findBrowserOfHourlyEventTag(result.tag);
		Location location = measuredValueTagService.findLocationOfHourlyEventTag(result.tag);

		servers.each{GraphiteServer graphiteServer ->
			log.debug("Sending results to the following GraphiteServer: ${graphiteServer.getServerAdress()}: ")
			
			GraphiteSocket socket
			try {
				log.info("now the graphiteSocket should be retrieved ...")
				socket = graphiteSocketProvider.getSocket(graphiteServer)
			} catch (Exception e) {
				//TODO: java.net.UnknownHostException can't be catched explicitly! Maybe groovy wraps the exception? But the stacktrace says java.net.UnknownHostException  ...
				log.error("GraphiteServer ${graphiteServer} couldn't be reached. The following result couldn't be be sent: ${result}")
				return
			}

			graphiteServer.graphitePaths.each { GraphitePath eachPath ->

				Boolean resultOfSameCachedViewAsGraphitePath = 
					eachPath.getMeasurand().isCachedCriteriaApplicable() &&
					resultMeasuredValueService.getAggregatorTypeCachedViewType(eachPath.getMeasurand()).equals(result.getCachedView());
					
				if(resultOfSameCachedViewAsGraphitePath){

					Double value=resultMeasuredValueService.getEventResultPropertyForCalculation(eachPath.getMeasurand(), result);
					if (value!=null) {
						if (eachPath.getMeasurand().getMeasurandGroup()==MeasurandGroup.PERCENTAGES) {
							value = value * 100
						}
						String measurandName = i18nService.msg(
								"de.iteratec.ispc.report.external.graphite.measurand.${eachPath.getMeasurand().getName()}", eachPath.getMeasurand().getName());
						
						List<String> pathElements = []
						pathElements.addAll(eachPath.getPrefix().tokenize('.'))
						pathElements.add(replaceInvalidGraphitePathCharacters(jobGroup.name))
						pathElements.add('raw')
						pathElements.add(replaceInvalidGraphitePathCharacters(page.name))
						pathElements.add(replaceInvalidGraphitePathCharacters(event.name))
						pathElements.add(replaceInvalidGraphitePathCharacters(browser.name))
						pathElements.add(replaceInvalidGraphitePathCharacters(location.uniqueIdentifierForServer==null ? location.location.toString():location.uniqueIdentifierForServer.toString()))
						pathElements.add(replaceInvalidGraphitePathCharacters(measurandName))
						
						GraphitePathName finalPathName
						try {
							finalPathName=GraphitePathName.valueOf(pathElements.toArray(new String[pathElements.size()]));
						} catch (IllegalArgumentException iae) {
							log.error("Couldn't write result to graphite due to invalid path: ${pathElements}", iae)
							return
						} catch (NullPointerException npe) {
							log.error("Couldn't write result to graphite due to invalid path: ${pathElements}", npe)
							return
						}
						try {
							socket.sendDate(finalPathName, value, result.getJobResultDate())
						} catch (NullPointerException npe) {
							log.error("Couldn't write result to graphite due to invalid path: ${pathElements}", npe)
						} catch (GraphiteComunicationFailureException e) {
							log.error(e)
						} 

						log.debug("Sent date to graphite: path=${finalPathName}, value=${value} time=${result.getJobResultDate().getTime()}")
					}
				}
			}
		}
	}

	/**
	 * <p>
	 * Reports the Event CSI values of the last full hour before(!) the given 
	 * reporting time-stamp to an external metric tool.
	 * </p>
	 * 
	 * @param reportingTimeStamp 
	 *         The time-stamp for that the last full interval before 
	 *         should be reported, not <code>null</code>.
	 * @since IT-199
	 */
	public void reportEventCSIValuesOfLastHour(DateTime reportingTimeStamp) {
		
		if ( ! configService.areMeasurementsGenerallyEnabled() ) {
			log.info("No event csi values are reported cause measurements are generally disabled.")
			return
		}

		Contract.requiresArgumentNotNull("reportingTimeStamp", reportingTimeStamp)

		if(log.debugEnabled) log.debug('reporting csi-values of last hour')
		Collection<JobGroup> csiGroupsWithGraphiteServers = jobGroupDaoService.findCSIGroups().findAll {it.graphiteServers.size()>0}
		if(log.debugEnabled) log.debug("csi-groups to report: ${csiGroupsWithGraphiteServers}")
		csiGroupsWithGraphiteServers.each {JobGroup eachJobGroup ->

			MvQueryParams queryParams = new MvQueryParams()
			queryParams.jobGroupIds.add(eachJobGroup.getId())
			Date startOfLastClosedInterval = measuredValueUtilService.resetToStartOfActualInterval(
				measuredValueUtilService.subtractOneInterval(reportingTimeStamp, MeasuredValueInterval.HOURLY), 
				MeasuredValueInterval.HOURLY)
				.toDate();
			List<MeasuredValue> mvs = eventMeasuredValueService.getHourylMeasuredValues(startOfLastClosedInterval, startOfLastClosedInterval, queryParams).findAll{MeasuredValue hmv ->
				hmv.value != null && hmv.countResultIds() > 0 
			}

			if(log.debugEnabled) log.debug("MeasuredValues to report for last hour: ${mvs}")
			reportAllMeasuredValuesFor(eachJobGroup, AggregatorType.MEASURED_EVENT, mvs)
		}
	}

	/**
	 * <p>
	 * Reports the Page CSI values of the last Day before(!) the given 
	 * reporting time-stamp to an external metric tool.
	 * </p>
	 * 
	 * @param reportingTimeStamp 
	 *         The time-stamp for that the last full interval before 
	 *         should be reported, not <code>null</code>.
	 * @since IT-201
	 */
	public void reportPageCSIValuesOfLastDay(DateTime reportingTimeStamp) {
		
		if ( ! configService.areMeasurementsGenerallyEnabled() ) {
			log.info("No page csi values of last day are reported cause measurements are generally disabled.")
			return
		}

		if (log.infoEnabled) log.info("Start reporting PageCSIValuesOfLastDay for timestamp: ${reportingTimeStamp}");
		Contract.requiresArgumentNotNull("reportingTimeStamp", reportingTimeStamp)

		reportPageCSIValues(MeasuredValueInterval.DAILY, reportingTimeStamp)
	}

	/**
	 * <p>
	 * Reports the Page CSI values of the last week before(!) the given
	 * reporting time-stamp to an external metric tool.
	 * </p>
	 *
	 * @param reportingTimeStamp
	 *         The time-stamp for that the last full interval before
	 *         should be reported, not <code>null</code>.
	 * @since IT-205
	 */
	public void reportPageCSIValuesOfLastWeek(DateTime reportingTimeStamp) {
		
		if ( ! configService.areMeasurementsGenerallyEnabled() ) {
			log.info("No page csi values of last week are reported cause measurements are generally disabled.")
			return
		}

		Contract.requiresArgumentNotNull("reportingTimeStamp", reportingTimeStamp)

		reportPageCSIValues(MeasuredValueInterval.WEEKLY, reportingTimeStamp)
	}

	private void reportPageCSIValues(Integer intervalInMinutes, DateTime reportingTimeStamp) {
		if(log.debugEnabled) log.debug("reporting page csi-values with intervalInMinutes ${intervalInMinutes} for reportingTimestamp: ${reportingTimeStamp}")

		jobGroupDaoService.findCSIGroups().findAll {it.graphiteServers.size()>0}.each {JobGroup eachJobGroup ->

			Date startOfLastClosedInterval = measuredValueUtilService.resetToStartOfActualInterval(
				measuredValueUtilService.subtractOneInterval(reportingTimeStamp, intervalInMinutes), 
				intervalInMinutes)
				.toDate();

			if(log.debugEnabled) log.debug("getting page csi-values to report to graphite: startOfLastClosedInterval=${startOfLastClosedInterval}")
			MeasuredValueInterval interval = MeasuredValueInterval.findByIntervalInMinutes(intervalInMinutes)
			List<MeasuredValue> pmvsWithData = pageMeasuredValueService.getOrCalculatePageMeasuredValues(startOfLastClosedInterval, startOfLastClosedInterval, interval, [eachJobGroup]).findAll{MeasuredValue pmv ->
				pmv.value != null && pmv.countResultIds() > 0 
			}

			if(log.debugEnabled) log.debug("reporting ${pmvsWithData.size()} page csi-values with intervalInMinutes ${intervalInMinutes} for JobGroup: ${eachJobGroup}");
			reportAllMeasuredValuesFor(eachJobGroup, AggregatorType.PAGE, pmvsWithData)
		}
	}

	/**
	 * <p>
	 * Reports the Shop CSI values of the last Day before(!) the given 
	 * reporting time-stamp to an external metric tool.
	 * </p>
	 * 
	 * @param reportingTimeStamp 
	 *         The time-stamp for that the last full interval before 
	 *         should be reported, not <code>null</code>.
	 * @since IT-203
	 */
	public void reportShopCSIValuesOfLastDay(DateTime reportingTimeStamp) {
		
		if ( ! configService.areMeasurementsGenerallyEnabled() ) {
			log.info("No shop csi values of last day are reported cause measurements are generally disabled.")
			return
		}

		Contract.requiresArgumentNotNull("reportingTimeStamp", reportingTimeStamp)

		reportShopCSIMeasuredValues(MeasuredValueInterval.DAILY, reportingTimeStamp)
	}

	/**
	 * <p>
	 * Reports the Shop CSI values of the last week before(!) the given
	 * reporting time-stamp to an external metric tool.
	 * </p>
	 *
	 * @param reportingTimeStamp
	 *         The time-stamp for that the last full interval before
	 *         should be reported, not <code>null</code>.
	 * @since IT-205
	 */
	public void reportShopCSIValuesOfLastWeek(DateTime reportingTimeStamp) {
		
		if ( ! configService.areMeasurementsGenerallyEnabled() ) {
			log.info("No shop csi values of last week are reported cause measurements are generally disabled.")
			return
		}

		Contract.requiresArgumentNotNull("reportingTimeStamp", reportingTimeStamp)

		reportShopCSIMeasuredValues(MeasuredValueInterval.WEEKLY, reportingTimeStamp)
	}

	private void reportShopCSIMeasuredValues(Integer intervalInMinutes, DateTime reportingTimeStamp) {
		if(log.debugEnabled) log.debug("reporting shop csi-values with intervalInMinutes ${intervalInMinutes} for reportingTimestamp: ${reportingTimeStamp}")

		jobGroupDaoService.findCSIGroups().findAll {it.graphiteServers.size()>0}.each {JobGroup eachJobGroup ->

			Date startOfLastClosedInterval = measuredValueUtilService.resetToStartOfActualInterval(
				measuredValueUtilService.subtractOneInterval(reportingTimeStamp, intervalInMinutes), 
				intervalInMinutes)
				.toDate();

			if(log.debugEnabled) log.debug("getting shop csi-values to report to graphite: startOfLastClosedInterval=${startOfLastClosedInterval}")
			MeasuredValueInterval interval = MeasuredValueInterval.findByIntervalInMinutes(intervalInMinutes)
			List<MeasuredValue> smvsWithData = shopMeasuredValueService.getOrCalculateShopMeasuredValues(startOfLastClosedInterval, startOfLastClosedInterval, interval, [eachJobGroup]).findAll {MeasuredValue smv ->
				smv.value != null && smv.countResultIds() > 0
			}

			reportAllMeasuredValuesFor(eachJobGroup, AggregatorType.SHOP, smvsWithData)
		}
	}

	private void reportAllMeasuredValuesFor(JobGroup jobGroup, String aggregatorName, List<MeasuredValue> mvs) {
		jobGroup.graphiteServers.each {eachGraphiteServer ->
			eachGraphiteServer.graphitePaths.findAll { it.measurand.name.equals(aggregatorName) }.each {GraphitePath measuredEventGraphitePath ->

				GraphiteSocket socket
				try {
					socket = graphiteSocketProvider.getSocket(eachGraphiteServer)
				} catch (Exception e){
					//TODO: java.net.UnknownHostException can't be catched explicitly! Maybe groovy wraps the exception? But the stacktrace says java.net.UnknownHostException  ...
					if (log.errorEnabled) {log.error("GraphiteServer ${eachGraphiteServer} couldn't be reached. ${mvs.size()} MeasuredValues couldn't be sent.")}
					return
				}
				
				if(log.debugEnabled) log.debug("${mvs.size()} MeasuredValues should be sent to:\nJobGroup=${jobGroup}\nGraphiteServer=${eachGraphiteServer.getServerAdress()}\nGraphitePath=${measuredEventGraphitePath}")
				mvs.each {MeasuredValue mv ->
					if(log.debugEnabled) log.debug("Sending ${mv.interval.name} ${aggregatorName}-csi-value for:\nJobGroup=${jobGroup}\nGraphiteServer=${eachGraphiteServer.getServerAdress()}\nGraphitePath=${measuredEventGraphitePath}")
					reportMeasuredValue(measuredEventGraphitePath.getPrefix(), jobGroup, mv, socket)
				}
			}
		}
	}


	private void reportMeasuredValue(String prefix, JobGroup jobGroup, MeasuredValue mv, GraphiteSocket socket){
		if (mv.interval.intervalInMinutes == MeasuredValueInterval.HOURLY && mv.aggregator.name.equals(AggregatorType.MEASURED_EVENT)) {
			reportHourlyMeasuredValue(prefix, jobGroup, mv, socket)
		}else if (mv.interval.intervalInMinutes == MeasuredValueInterval.DAILY){
			if (mv.aggregator.name.equals(AggregatorType.PAGE)) {
				reportDailyPageMeasuredValue(prefix, jobGroup, mv, socket)
			} else if (mv.aggregator.name.equals(AggregatorType.SHOP)) {
				reportDailyShopMeasuredValue(prefix, jobGroup, mv, socket)
			}
		} else if (mv.interval.intervalInMinutes == MeasuredValueInterval.WEEKLY){
			if (mv.aggregator.name.equals(AggregatorType.PAGE)) {
				reportWeeklyPageMeasuredValue(prefix, jobGroup, mv, socket)
			} else if (mv.aggregator.name.equals(AggregatorType.SHOP)) {
				reportWeeklyShopMeasuredValue(prefix, jobGroup, mv, socket)
			}
		}
	}

	private void reportHourlyMeasuredValue(String prefix, JobGroup jobGroup, MeasuredValue mv, GraphiteSocket socket) {
		Page page = measuredValueTagService.findPageOfHourlyEventTag(mv.tag)
		MeasuredEvent event = measuredValueTagService.findMeasuredEventOfHourlyEventTag(mv.tag)
		Browser browser = measuredValueTagService.findBrowserOfHourlyEventTag(mv.tag)
		Location location = measuredValueTagService.findLocationOfHourlyEventTag(mv.tag)
		
		List<String> pathElements = []
		pathElements.addAll(prefix.tokenize('.'))
		pathElements.add(replaceInvalidGraphitePathCharacters(jobGroup.name))
		pathElements.add('hourly')
		pathElements.add(replaceInvalidGraphitePathCharacters(page.name))
		pathElements.add(replaceInvalidGraphitePathCharacters(event.name))
		pathElements.add(replaceInvalidGraphitePathCharacters(browser.name))
		pathElements.add(replaceInvalidGraphitePathCharacters(location.uniqueIdentifierForServer==null ? location.location.toString():location.uniqueIdentifierForServer.toString()))
		pathElements.add('csi')
		
		GraphitePathName finalPathName=GraphitePathName.valueOf(pathElements.toArray(new String[pathElements.size()]));
		double valueAsPercentage = mv.value * 100
		if(log.debugEnabled) log.debug("Sending ${mv.started}|${valueAsPercentage} as hourly MeasuredValue to graphite-path ${finalPathName}")
		socket.sendDate(finalPathName, valueAsPercentage, mv.started)
	}

	private void reportDailyPageMeasuredValue(String prefix, JobGroup jobGroup, MeasuredValue mv, GraphiteSocket socket) {
		Page page = measuredValueTagService.findPageOfDailyPageTag(mv.tag)

		List<String> pathElements = []
		pathElements.addAll(prefix.tokenize('.'))
		pathElements.add(replaceInvalidGraphitePathCharacters(jobGroup.name))
		pathElements.add('daily')
		pathElements.add(replaceInvalidGraphitePathCharacters(page.name))
		pathElements.add('csi')
		
		GraphitePathName finalPathName=GraphitePathName.valueOf(pathElements.toArray(new String[pathElements.size()]));
		double valueAsPercentage = mv.value * 100
		if(log.debugEnabled) log.debug("Sending ${mv.started}|${valueAsPercentage} as daily page-MeasuredValue to graphite-path ${finalPathName}")
		socket.sendDate(finalPathName, valueAsPercentage, mv.started)
	}

	private void reportDailyShopMeasuredValue(String prefix, JobGroup jobGroup, MeasuredValue mv, GraphiteSocket socket) {
		List<String> pathElements = []
		pathElements.addAll(prefix.tokenize('.'))
		pathElements.add(replaceInvalidGraphitePathCharacters(jobGroup.name))
		pathElements.add('daily')
		pathElements.add('csi')
		
		GraphitePathName finalPathName=GraphitePathName.valueOf(pathElements.toArray(new String[pathElements.size()]));
		double valueAsPercentage = mv.value * 100
		if(log.debugEnabled) log.debug("Sending ${mv.started}|${valueAsPercentage} as daily shop- MeasuredValue to graphite-path ${finalPathName}")
		socket.sendDate(finalPathName, valueAsPercentage, mv.started)
	}

	private void reportWeeklyPageMeasuredValue(String prefix, JobGroup jobGroup, MeasuredValue mv, GraphiteSocket socket) {
		Page page = measuredValueTagService.findPageOfWeeklyPageTag(mv.tag)
		
		List<String> pathElements = []
		pathElements.addAll(prefix.tokenize('.'))
		pathElements.add(replaceInvalidGraphitePathCharacters(jobGroup.name))
		pathElements.add('weekly')
		pathElements.add(replaceInvalidGraphitePathCharacters(page.name))
		pathElements.add('csi')
		
		GraphitePathName finalPathName=GraphitePathName.valueOf(pathElements.toArray(new String[pathElements.size()]));
		double valueAsPercentage = mv.value * 100
		if(log.debugEnabled) log.debug("Sending ${mv.started}|${valueAsPercentage} as weekly page-MeasuredValue to graphite-path ${finalPathName}")
		socket.sendDate(finalPathName, valueAsPercentage, mv.started)
	}

	private void reportWeeklyShopMeasuredValue(String prefix, JobGroup jobGroup, MeasuredValue mv, GraphiteSocket socket) {
		List<String> pathElements = []
		pathElements.addAll(prefix.tokenize('.'))
		pathElements.add(replaceInvalidGraphitePathCharacters(jobGroup.name))
		pathElements.add('weekly')
		pathElements.add('csi')
		
		GraphitePathName finalPathName=GraphitePathName.valueOf(pathElements.toArray(new String[pathElements.size()]));
		double valueAsPercentage = mv.value * 100
		if(log.debugEnabled) log.debug("Sending ${mv.started}|${valueAsPercentage} as weekly shop-MeasuredValue to graphite-path ${finalPathName}")
		socket.sendDate(finalPathName, valueAsPercentage, mv.started)
	}
	
	private String replaceInvalidGraphitePathCharacters(String graphitePathElement){
		String replaced = graphitePathElement
		INVALID_GRAPHITE_PATH_CHARACTERS.each {String invalidChar ->
			replaced = replaced.replace(invalidChar, REPLACEMENT_FOR_INVALID_GRAPHITE_PATH_CHARACTERS)
		}
		return replaced
	}
}
