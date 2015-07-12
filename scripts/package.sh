#/bin/bash -e
# Copyright 2015 Treode, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

create_debian_package () {
    PKGVER=$1
    WGETFILE=$2
    JARFILE=$3
    OUTDIR=$4

    echo "vars $PKGVER $WGETFILE $JARFILE $OUTDIR"

    TMPDIR=/var/tmp/treode.$$
    STAGEDIR=${TMPDIR}/treode-${PKGVER}
    mkdir -p ${STAGEDIR}

    sed -e "s/#TREODEPKGVERSION#/${PKGVER}/" ${SCRIPT_DIR}/conf/treode.init >  ${STAGEDIR}/treode.init
    cd ${STAGEDIR}

    if [[ $WGETFILE == 1 ]]; then
        wget https://oss.treode.com/jars/${PKGVER}/server-${PKGVER}.jar
    else
        echo COPY $JARFILE to `pwd`
        cp $JARFILE .
    fi

    LOGNAME=questions@treode.com
    DEBFULLNAME="Treode Inc"
    dh_make -y -n -s -c apache -e ${LOGNAME}

    # Customize debian control files
    cat ${SCRIPT_DIR}/conf/treode.rules >> debian/rules
    sed -i -e '/^Depends:/ s/$/, \${treode:Depends}/' debian/control
    sed -i -e 's/^Homepage:/Homepage: http:\/\/www.treode.com/' debian/control
    sed -i -e '/^Description:/{N;s/.*/Description: The DB that\x27s replicated, sharded and transactional.\
 TreodeDB is an open-source NoSQL database that shards for scalability, replicates for \
 reliability, and yet provides full ACID transactions.\
 TreodeDB connects to Spark for analytics, and it integrates well with CDNs for speed.\
 TreodeDB lets engineers develop the application, rather than work around the data architecture./}' debian/control

    export DESTDIR=`pwd`/debian
    fakeroot debian/rules clean
    PKGVER="${PKGVER}" fakeroot debian/rules binary

    # If successful, copy .deb to $SCRIPT_DIR/o
    if [ -e ${STAGEDIR}/../*.deb ]; then
        mv ${STAGEDIR}/../*.deb ${OUTDIR}
        echo "Removing stagedir ${TMPDIR}"
        rm -rf ${TMPDIR}
    else
        echo "THERE WAS AN ERROR CREATING .DEB PACKAGE"
        echo "STAGE DIR ${STAGEDIR}"
    fi

}

print_usage() {
    cat << EOF
Usage: $0 [-t <packagetype>][-V <packageversion>][-o <stagedir>][-j <jarfile to package>]

Examples:
Package 0.3.0 server in debian format after building the assembly jar.
$0 -t debian -V 0.3.0 -j stagedir/server-0.3.0-SNAPSHOT.jar -o stagedir

Package 0.2.0 release finagle example in debian pkg format from Ivy OSS.
$0 -t debian -V 0.2.0

EOF
}

PKGTYPE=
PKGVER=
JARFILE=
OUTDIR=
WGETFILE=0
while getopts "t:V:j:o:" OPTION
do
    case $OPTION in
        t)
            PKGTYPE=$OPTARG
            ;;
        V)
            PKGVER=$OPTARG
            ;;
        j)
            JARFILE=`readlink -f $OPTARG`
            ;;
        o)
            OUTDIR=`readlink -f $OPTARG`
            ;;
        *)
            print_usage
            exit
            ;;
    esac
done

SCRIPT_DIR=$(dirname $(readlink -f $0))

if [[ -z $PKGTYPE ]] || [[ -z $PKGVER ]]; then
    echo "Please provide package type and version using -t and -V options"
    exit 1
fi

if [[ -z $OUTDIR ]] ; then
    echo "Please provide output directory using -o option"
    exit 1
fi

if [[ -z $JARFILE ]] ; then
    WGETFILE=1
    JARFILE="https://oss.treode.com/jars/${PKGVER}/server-${PKGVER}.jar"
    echo "Using $JARFILE"
fi

case $PKGTYPE in
    debian)
        echo "PACKAGING TREODE FOR Debian..."
        create_debian_package $PKGVER $WGETFILE $JARFILE $OUTDIR
        ;;
    ?)
        echo "PACKAGING TREODE FOR $PKGTYPE is not implemented"
        exit 1
        ;;
esac
