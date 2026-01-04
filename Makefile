.PHONY: run test uberjar clean

# Development
run:
	clojure -M:run

test:
	clojure -M:test

# Build executable JAR
uberjar:
	clojure -T:build uberjar

clean:
	rm -rf target
