all: build test

clean:
	mvn clean

build:
	mvn compile
	
package: build
	mvn package
	cp target/timeseries-service-*.jar ${REPRO_MNT}/service/

test: build package
	make -C ../service install
	make -C ../service run &
	make -C ../javascript install test
