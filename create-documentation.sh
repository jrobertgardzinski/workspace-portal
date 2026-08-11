#!/bin/bash
# Same entry point as shared/create-documentation.sh: regenerate the versioned docs.
# Run the test suites first (./mvnw clean test; microservice-image: pytest --alluredir).

python3 "build_documentation.py"
