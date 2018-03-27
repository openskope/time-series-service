package org.openskope.timeseries.service;

import org.openskope.timeseries.controller.InvalidArgumentException;
import org.openskope.timeseries.model.TimeseriesRequest;
import org.openskope.timeseries.model.TimeseriesResponse;
import org.yesworkflow.util.exec.ProcessRunner;
import org.yesworkflow.util.exec.StreamSink;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriTemplate;

import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Service
public class TimeseriesService implements InitializingBean {
    
	@Value("${TIMESERIES_DATA_PATH_TEMPLATE}") public String timeseriesDataPath;
	@Value("${TIMESERIES_DATA_FILE_EXTENSIONS}") public String timeseriesDatafileExtensions;

    private UriTemplate dataPathTemplate;
    private Map<String, String> uriVariables = new HashMap<String,String>();
    private String[] extensions;

    public void afterPropertiesSet() {
    	
    	dataPathTemplate = new UriTemplate(timeseriesDataPath);
    	
    	String[] customExtensionArray = {};
    	if (timeseriesDatafileExtensions.trim().length() > 0) {
    		customExtensionArray = timeseriesDatafileExtensions.split("\\s+");
    	}
    	extensions = new String[1 + customExtensionArray.length];
    	extensions[0] = "";
    	int i = 1;
    	for (String customExtension : customExtensionArray) {
    		extensions[i++] = customExtension;
    	}
    }

	public TimeseriesResponse getTimeseries(
			TimeseriesRequest request
	) throws Exception {

        File dataFile = getDataFile(request.getDatasetId(), request.getVariableName());
        if (dataFile == null) {
        	throw new InvalidArgumentException(
    			"Data file for dataset '" + request.getDatasetId() +
    			"', variable '" + request.getVariableName() + 
    			"' does not exist on timeseries server."
			);
        }
        
        String[] stringOutputValues = runGdalLocationInfo(dataFile, request.getLongitude(), request.getLatitude());
        if (stringOutputValues.length == 0) {
        	throw new InvalidArgumentException("Coordinates are outside region covered by the dataset");
        }

        Integer rangeStart = (request.getStart() == null) ? 0 : Integer.parseInt(request.getStart());
        if (rangeStart > stringOutputValues.length - 1) {
        	throw new InvalidArgumentException("Time range start is outside coverage of dataset");
        }
        
    	Integer rangeEnd = (request.getEnd() == null) ? stringOutputValues.length - 1: Integer.parseInt(request.getEnd());
        if (rangeEnd > stringOutputValues.length - 1) {
        	rangeEnd = stringOutputValues.length - 1;
        }
        
        if (rangeEnd < rangeStart) {
        	throw new InvalidArgumentException("Time range end is before time range start");
        }

        int[] valuesInRequestedRange = getRangeOfStringValuesAsInts(stringOutputValues, rangeStart, rangeEnd);
        
        int[] values =  request.getReturnArray() ? valuesInRequestedRange : null;
        String csv = request.getReturnCsv() ? getTable(request, valuesInRequestedRange) : null;
		
        return new TimeseriesResponse(
        		request.getDatasetId(),
        		request.getVariableName(),
        		request.getLatitude(),
        		request.getLongitude(), 
        		rangeStart, 
        		rangeEnd, 
        		values,
        		csv
    		);
	}
	
	private File getDataFile(String datasetId, String variableName) {
        uriVariables.put("datasetId", datasetId);
        uriVariables.put("variableName", variableName);
		URI datafileBaseUri = dataPathTemplate.expand(uriVariables);
		for (String extension : extensions) {
			File file = new File(datafileBaseUri.getPath() + extension);
			if (file.exists()) {
				return file;
			}
		}
		return null;
	}
	
	private String[] runGdalLocationInfo(File dataFile, double longitude, double latitude) throws Exception {
        String commandLine = String.format(
                "gdallocationinfo -valonly -geoloc %s %f %f", dataFile.getAbsolutePath(), longitude, latitude);
        System.out.println(commandLine);
        StreamSink streams[] = ProcessRunner.run(commandLine, "", new String[0], null);
        return streams[0].toString().split("\\s+");
	}
	
	private int[] getRangeOfStringValuesAsInts(String[] sa, int start, int end) {
		int[] ia = new int[end - start + 1];
		for (int si = 0, ii = 0; si < sa.length; ++si) {
			if (si >= start && si <= end) {
				ia[ii++] = Integer.valueOf(sa[si]);
			}
		}
		return ia;
	}
	
	public String getTable(TimeseriesRequest request, int[] values) throws Exception {
		StringBuffer buffer = new StringBuffer();
        buffer.append("Year, " + request.getVariableName() + "\n");
        for (int i = 0; i < values.length; ++i) {
            buffer.append(String.format("%d, %s\n", i + 1, values[i]));
        }
        return buffer.toString();
	}
}